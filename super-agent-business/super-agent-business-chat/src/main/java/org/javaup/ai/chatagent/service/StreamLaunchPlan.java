package org.javaup.ai.chatagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.javaup.enums.ChatQueryMode;

import java.time.LocalDate;

/**
 * @description: 服务层
 **/

@Data
@AllArgsConstructor
public class StreamLaunchPlan {

    private final String question;

    private final String conversationId;

    private final ChatQueryMode chatMode;

    private final Long selectedDocumentId;

    private final String selectedDocumentName;

    private final Long selectedTaskId;

    private final String leaseKey;

    private final String leaseOwnerToken;

    private final LocalDate currentDate;

    private final String currentDateText;
}
