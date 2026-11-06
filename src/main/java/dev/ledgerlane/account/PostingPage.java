package dev.ledgerlane.account;

import java.util.List;

/** Newest first. Pass nextCursor as the cursor parameter to get the following page; null on the last page. */
public record PostingPage(List<PostingView> items, String nextCursor) {
}
