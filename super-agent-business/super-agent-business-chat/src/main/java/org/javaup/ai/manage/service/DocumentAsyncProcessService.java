package org.javaup.ai.manage.service;



public interface DocumentAsyncProcessService {

    void handleParseRoute(Long documentId, Long taskId);

    void handleIndexBuild(Long documentId, Long taskId, Long planId);
}
