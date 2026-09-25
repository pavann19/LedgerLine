package com.ledgerline.projection.model;

import java.util.List;

public record CursorPage<T>(List<T> items, String nextCursor) {}
