package zzz.service.forum.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiInviteCodeEntity {

    private Long id;
    private String code;
    private Long createdBy;
    private Long usedBy;
    private Integer status;
    private LocalDateTime expireAt;
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;
}
