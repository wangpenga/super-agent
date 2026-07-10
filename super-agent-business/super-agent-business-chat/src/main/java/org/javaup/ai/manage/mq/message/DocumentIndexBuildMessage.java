package org.javaup.ai.manage.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;



@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIndexBuildMessage {

    private Long documentId;

    private Long taskId;

    private Long planId;
}
