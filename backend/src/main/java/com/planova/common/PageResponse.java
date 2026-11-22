package com.planova.common;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Our own pagination envelope. Returning Spring's {@code PageImpl} directly is discouraged because
 * its JSON shape is not a stable contract; this record is.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
