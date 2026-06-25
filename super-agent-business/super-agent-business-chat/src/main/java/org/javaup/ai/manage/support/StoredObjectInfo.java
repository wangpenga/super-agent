package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;



@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoredObjectInfo {

    private String bucketName;

    private String objectName;

    private String objectUrl;
}
