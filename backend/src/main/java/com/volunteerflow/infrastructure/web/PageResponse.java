package com.volunteerflow.infrastructure.web;

import java.util.List;

/** Additive pagination envelope: existing clients can continue reading the data array. */
public record PageResponse<T>(List<T> data, Meta meta) {
  public record Meta(long total, long page, long size) {}

  public static <T> PageResponse<T> of(List<T> items, long total, long page, long size) {
    return new PageResponse<>(List.copyOf(items), new Meta(total, page, size));
  }
}
