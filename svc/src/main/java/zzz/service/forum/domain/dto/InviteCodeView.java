package zzz.service.forum.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InviteCodeView {

    private Long id;
    private String code;
    private Integer status;
    private boolean expired;
    private LocalDateTime expireAt;
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;
    private Long usedBy;
    private String usedByUsername;
}
