package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

@Data
public class PageResult<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    private List<T> records;
    private long total;
    private int page;
    private int pageSize;
    private int pages;

    public PageResult(List<T> records, long total, int page, int pageSize) {
        this.records = records == null ? Collections.emptyList() : records;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
        this.pages = pageSize <= 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
    }
}
