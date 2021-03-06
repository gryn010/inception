/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.conceptlinking.service;

import static de.tudarmstadt.ukp.clarin.webanno.api.annotation.util.WebAnnoCasUtil.selectSentenceAt;
import static de.tudarmstadt.ukp.clarin.webanno.api.annotation.util.WebAnnoCasUtil.selectTokensCovered;
import static de.tudarmstadt.ukp.inception.conceptlinking.model.CandidateEntity.KEY_MENTION;
import static de.tudarmstadt.ukp.inception.conceptlinking.model.CandidateEntity.KEY_MENTION_CONTEXT;
import static de.tudarmstadt.ukp.inception.conceptlinking.model.CandidateEntity.KEY_QUERY;
import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableList;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.CompareToBuilder;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.text.AnnotationFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.inception.conceptlinking.config.EntityLinkingProperties;
import de.tudarmstadt.ukp.inception.conceptlinking.model.CandidateEntity;
import de.tudarmstadt.ukp.inception.conceptlinking.service.feature.EntityRankingFeatureGenerator;
import de.tudarmstadt.ukp.inception.conceptlinking.util.FileUtils;
import de.tudarmstadt.ukp.inception.kb.ConceptFeatureValueType;
import de.tudarmstadt.ukp.inception.kb.KnowledgeBaseService;
import de.tudarmstadt.ukp.inception.kb.RepositoryType;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilder;
import de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryPrimaryConditions;

@Component
public class ConceptLinkingServiceImpl
    implements InitializingBean, ConceptLinkingService
{
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final KnowledgeBaseService kbService;
    private final EntityLinkingProperties properties;

    @Value(value = "${repository.path}/resources/stopwords-en.txt")
    private File stopwordsFile;
    private Set<String> stopwords;

    private final List<EntityRankingFeatureGenerator> featureGeneratorsProxy;
    private List<EntityRankingFeatureGenerator> featureGenerators;

    @Autowired
    public ConceptLinkingServiceImpl(KnowledgeBaseService aKbService,
            EntityLinkingProperties aProperties,
            @Lazy @Autowired(required = false) List<EntityRankingFeatureGenerator> 
                    aFeatureGenerators)
    {
        Validate.notNull(aKbService);
        Validate.notNull(aProperties);
        
        kbService = aKbService;
        properties = aProperties;
        featureGeneratorsProxy = aFeatureGenerators;
    }

    @Override
    public void afterPropertiesSet() throws Exception
    {
        if (stopwordsFile != null) {
            stopwords = FileUtils.loadStopwordFile(stopwordsFile);
        }
        else {
            stopwords = emptySet();
        }
    }
    
    @EventListener
    public void onContextRefreshedEvent(ContextRefreshedEvent aEvent)
    {
        init();
    }

    /* package private */ void init()
    {
        List<EntityRankingFeatureGenerator> generators = new ArrayList<>();

        if (featureGeneratorsProxy != null) {
            generators.addAll(featureGeneratorsProxy);
            AnnotationAwareOrderComparator.sort(generators);
        
            for (EntityRankingFeatureGenerator generator : generators) {
                log.info("Found entity ranking feature generator: {}",
                        ClassUtils.getAbbreviatedName(generator.getClass(), 20));
            }
        }

        featureGenerators = unmodifiableList(generators);
    }

    private SPARQLQueryPrimaryConditions newQueryBuilder(ConceptFeatureValueType aValueType,
            KnowledgeBase aKB)
    {
        switch (aValueType) {
        case ANY_OBJECT:
            return SPARQLQueryBuilder.forItems(aKB);
        case CONCEPT:
            return SPARQLQueryBuilder.forClasses(aKB);
        case INSTANCE:
            return SPARQLQueryBuilder.forInstances(aKB);
        default:
            throw new IllegalArgumentException("Unknown item type: [" + aValueType + "]");
        }
    }
    
    public Set<KBHandle> generateCandidates(KnowledgeBase aKB, String aConceptScope,
            ConceptFeatureValueType aValueType, String aQuery, String aMention)
    {
        // If the query of the user is smaller or equal to this threshold, then we only use it for
        // exact matching. If it is longer, we look for concepts which start with or which contain
        // the users input. This is meant as a performance optimization for large KBs where we 
        // want to avoid long reaction times when there is large number of candidates (which is
        // very likely when e.g. searching for all items starting with or containing a specific
        // letter.
        final int threshold = RepositoryType.LOCAL.equals(aKB.getType()) ? 0 : 3;
        
        long startTime = currentTimeMillis();
        Set<KBHandle> result = new HashSet<>();
        
        try (RepositoryConnection conn = kbService.getConnection(aKB)) {
            // Collect exact matches - although exact matches are theoretically contained in the
            // set of containing matches, due to the ranking performed by the KB/FTS, we might
            // not actually see the exact matches within the first N results. So we query for
            // the exact matches separately to ensure we have them.
            String[] exactLabels = asList(
                    (aQuery != null && aQuery.length() <= threshold) ? aQuery : null, aMention)
                    .stream()
                    .filter(Objects::nonNull)
                    .toArray(String[]::new);
            SPARQLQueryPrimaryConditions exactBuilder = newQueryBuilder(aValueType, aKB)
                    .withLabelMatchingExactlyAnyOf(exactLabels);
            
            if (aConceptScope != null) {
                exactBuilder.childrenOf(aConceptScope);
            }
            
            List<KBHandle> exactMatches = exactBuilder
                    .retrieveLabel()
                    .retrieveDescription()
                    .asHandles(conn, true);

            log.debug("Found [{}] candidates exactly matching {}",
                    exactMatches.size(), asList(exactLabels));

            result.addAll(exactMatches);

            if (aQuery != null && aQuery.length() > threshold) {
                // Collect matches starting with the query - this is the main driver for the
                // auto-complete functionality
                SPARQLQueryPrimaryConditions startingWithBuilder = newQueryBuilder(aValueType, aKB)
                        .withLabelStartingWith(aQuery);
                
                if (aConceptScope != null) {
                    startingWithBuilder.childrenOf(aConceptScope);
                }
                
                List<KBHandle> startingWithMatches = startingWithBuilder
                        .retrieveLabel()
                        .retrieveDescription()
                        .asHandles(conn, true);
                
                log.debug("Found [{}] candidates starting with [{}]]",
                        startingWithMatches.size(), aQuery);            
                
                result.addAll(startingWithMatches);
            }
            
            
            // Collect containing matches
            String[] containingLabels = asList(
                    (aQuery != null && aQuery.length() > threshold) ? aQuery : null, aMention)
                    .stream()
                    .filter(Objects::nonNull)
                    .toArray(String[]::new);
            SPARQLQueryPrimaryConditions containingBuilder = newQueryBuilder(aValueType, aKB)
                    .withLabelContainingAnyOf(containingLabels);
            
            if (aConceptScope != null) {
                containingBuilder.childrenOf(aConceptScope);
            }
            
            List<KBHandle> containingMatches = containingBuilder
                    .retrieveLabel()
                    .retrieveDescription()
                    .asHandles(conn, true);
            
            log.debug("Found [{}] candidates using containing {}",
                    containingMatches.size(), asList(containingLabels));
            
            result.addAll(containingMatches);
        }

        log.debug("Generated [{}] candidates in {}ms", result.size(),
                currentTimeMillis() - startTime);

        return result;
    }
    
    @Override
    public List<KBHandle> disambiguate(KnowledgeBase aKB, String aConceptScope,
            ConceptFeatureValueType aValueType, String aQuery, String aMention,
            int aMentionBeginOffset, CAS aCas)
    {
        Set<KBHandle> candidates = generateCandidates(aKB, aConceptScope, aValueType, aQuery,
                aMention);
        return rankCandidates(aQuery, aMention, candidates, aCas, aMentionBeginOffset);
    }

    private CandidateEntity initCandidate(CandidateEntity candidate, String aQuery, String aMention,
            CAS aCas, int aBegin)
    {
        candidate.put(KEY_MENTION, aMention);
        candidate.put(KEY_QUERY, aQuery);
        
        if (aCas != null) {
            AnnotationFS sentence = selectSentenceAt(aCas, aBegin);
            if (sentence != null) {
                List<String> mentionContext = new ArrayList<>();
                Collection<AnnotationFS> tokens = selectTokensCovered(sentence);
                // Collect left context
                tokens.stream()
                        .filter(t -> t.getEnd() <= aBegin)
                        .sorted(Comparator.comparingInt(AnnotationFS::getBegin).reversed())
                        .limit(properties.getMentionContextSize())
                        .map(t -> t.getCoveredText().toLowerCase(candidate.getLocale()))
                        .filter(s -> !stopwords.contains(s))
                        .forEach(mentionContext::add);
                // Collect right context
                tokens.stream()
                        .filter(t -> t.getBegin() >= (aBegin + aMention.length()))
                        .limit(properties.getMentionContextSize())
                        .map(t -> t.getCoveredText().toLowerCase(candidate.getLocale()))
                        .filter(s -> !stopwords.contains(s))
                        .forEach(mentionContext::add);
                candidate.put(KEY_MENTION_CONTEXT, mentionContext);
            }
            else {
                log.warn("Mention sentence could not be determined. Skipping.");
            }
        }
        return candidate;
    }
    
    private Comparator<CandidateEntity> baseLineRankingStrategy()
    {
        return (e1, e2) -> new CompareToBuilder()
                // The edit distance between query and label is given high importance
                // Comparing simultaneously against the edit distance to the query and to the 
                // mention causes items similar to either to be ranked up
                .append(Math.min(e1.getLevQuery(), e1.getLevMention()),
                        Math.min(e2.getLevQuery(), e2.getLevMention()))
                // A high signature overlap score is preferred.
                .append(e2.getSignatureOverlapScore(), e1.getSignatureOverlapScore())
                // A low edit distance is preferred.
                .append(e1.getLevContext(), e2.getLevContext())
                // A high entity frequency is preferred.
                .append(e2.getFrequency(), e1.getFrequency())
                // A high number of related relations is preferred.
                .append(e2.getNumRelatedRelations(), e1.getNumRelatedRelations())
                // A low wikidata ID rank is preferred.
                .append(e1.getIdRank(), e2.getIdRank())
                // Finally order alphabetically
                .append(e1.getLabel().toLowerCase(e1.getLocale()), 
                        e2.getLabel().toLowerCase(e2.getLocale()))
                .toComparison();
    }
    
    @Override
    public List<KBHandle> rankCandidates(String aQuery, String aMention, Set<KBHandle> aCandidates,
            CAS aCas, int aBegin)
    {
        long startTime = currentTimeMillis();
        
        // Set the feature values
        List<CandidateEntity> candidates = aCandidates.parallelStream()
                .map(CandidateEntity::new)
                .map(candidate -> initCandidate(candidate, aQuery, aMention, aCas, aBegin))
                .map(candidate -> {
                    for (EntityRankingFeatureGenerator generator : featureGenerators) {
                        generator.apply(candidate);
                    }
                    return candidate;
                })
                .collect(Collectors.toCollection(ArrayList::new));
        
        // Do the main ranking
        // Sort candidates by multiple keys.
        candidates.sort(baseLineRankingStrategy());

        List<KBHandle> results = candidates.stream()
                .map(candidate -> {
                    KBHandle handle = candidate.getHandle();
                    handle.setDebugInfo(String.valueOf(candidate.getFeatures()));
                    return handle;
                })
                .limit(properties.getCandidateDisplayLimit())
                .collect(Collectors.toList());
         
        log.debug("Ranked [{}] candidates for mention [{}] and query [{}] in [{}] ms",
                 results.size(), aMention, aQuery, currentTimeMillis() - startTime);
         
        return results;
    }

    @Override
    public List<KBHandle> getLinkingInstancesInKBScope(String aRepositoryId, String aConceptScope,
            ConceptFeatureValueType aValueType, String aQuery, String aMention,
            int aMentionBeginOffset, CAS aCas, Project aProject)
    {
        // Sanitize query by removing typical wildcard characters
        String query = aQuery.replaceAll("[*?]", "").trim();
        
        // Determine which knowledge bases to query
        List<KnowledgeBase> knowledgeBases = new ArrayList<>();
        if (aRepositoryId != null) {
            kbService.getKnowledgeBaseById(aProject, aRepositoryId).filter(KnowledgeBase::isEnabled)
                    .ifPresent(knowledgeBases::add);
        }
        else {
            knowledgeBases.addAll(kbService.getEnabledKnowledgeBases(aProject));
        }
        
        // Query the knowledge bases for candidates
        Set<KBHandle> candidates = new HashSet<>();
        for (KnowledgeBase kb : knowledgeBases) {
            candidates.addAll(
                    generateCandidates(kb, aConceptScope, aValueType, query, aMention));
        }
        
        // Rank the candidates and return them
        return rankCandidates(query, aMention, candidates, aCas, aMentionBeginOffset);
    }

    /**
     * Find KB items (classes and instances) matching the given query.
     */
    @Override
    public List<KBHandle> searchItems(KnowledgeBase aKB, String aQuery)
    {
        return disambiguate(aKB, null, ConceptFeatureValueType.ANY_OBJECT, aQuery, null, 0, null);
    }
}
