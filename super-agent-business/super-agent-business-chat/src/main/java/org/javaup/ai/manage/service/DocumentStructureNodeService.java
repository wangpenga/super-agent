package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.support.DocumentStructureNodeCandidate;

import java.util.List;
import java.util.Map;



public interface DocumentStructureNodeService {

    List<SuperAgentDocumentStructureNode> replaceDocumentNodes(Long documentId,
                                                               Long parseTaskId,
                                                               List<DocumentStructureNodeCandidate> candidates);

    List<SuperAgentDocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId);

    Map<Long, SuperAgentDocumentStructureNode> nodeMap(Long documentId, Long parseTaskId);

    void deleteByDocumentId(Long documentId);
}
