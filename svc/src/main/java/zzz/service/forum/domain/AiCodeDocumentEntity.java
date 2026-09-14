package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** 代码文件索引元数据，不保存源码正文。 */
@Data
public class AiCodeDocumentEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long projectId;
    private String path;
    private String language;
    private String checksum;
    private String symbolName;
    private String summary;
    private Integer startLine;
    private Integer endLine;
    private Date createdAt;
    private Date updatedAt;
}
