package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.route.KnowledgeRouteDecision;


public interface KnowledgeRouteService {

    KnowledgeRouteDecision route(String question, String rewriteQuestion);

    void recordShadowRoute(String conversationId,
                           long exchangeId,
                           Long selectedDocumentId,
                           String question,
                           String rewriteQuestion);

    void recordAutoRoute(String conversationId,
                         long exchangeId,
                         String question,
                         String rewriteQuestion,
                         KnowledgeRouteDecision decision);
}
