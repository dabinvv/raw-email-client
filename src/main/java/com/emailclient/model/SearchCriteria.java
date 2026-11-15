package com.emailclient.model;

/**
 * Encapsulates search parameters for local inbox search.
 *
 * Currently supports:
 * - Case-insensitive keyword matching
 * - Searches across subject, sender, recipient, and body
 *
 * Future extensions could add:
 * - Date range filtering
 * - Field-specific search (e.g., subject-only)
 * - Regex support
 */
public class SearchCriteria {

    private final String query;

    public SearchCriteria(String query) {
        this.query = query == null ? "" : query.trim();
    }

    public String getQuery() {
        return query;
    }

    /**
     * Returns true if this criteria has a non-empty search query.
     */
    public boolean hasQuery() {
        return !query.isEmpty();
    }

    /**
     * Checks if the given text matches this search criteria.
     * Matching is case-insensitive.
     *
     * @param text The text to search within
     * @return true if text contains the query (case-insensitive), or if query is empty
     */
    public boolean matches(String text) {
        if (!hasQuery()) return true;
        if (text == null) return false;
        return text.toLowerCase().contains(query.toLowerCase());
    }

    @Override
    public String toString() {
        return "SearchCriteria{query='" + query + "'}";
    }
}