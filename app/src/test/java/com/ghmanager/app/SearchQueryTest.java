package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SearchQueryTest {
    @Test
    public void normalizeCollapsesWhitespaceAndCaps() {
        assertEquals("a b c", SearchQuery.normalize("  a \n\t b   c "));
        assertEquals("", SearchQuery.normalize(null));
        assertEquals(256, SearchQuery.normalize(new String(new char[300]).replace('\0', 'x')).length());
    }

    @Test
    public void minimumLength() {
        assertFalse(SearchQuery.valid("a"));
        assertTrue(SearchQuery.valid("ab"));
    }

    @Test
    public void scopeByKind() {
        assertEquals("foo user:me", SearchQuery.build("foo", "", "me", false, SearchQuery.REPOS));
        assertEquals("foo", SearchQuery.build("foo", "", "me", true, SearchQuery.REPOS));
        assertEquals("foo user:me", SearchQuery.build("foo", "", "me", true, SearchQuery.CODE));
        assertEquals("foo", SearchQuery.build("foo", "o/r", "me", false, SearchQuery.REPOS));
        assertEquals("foo repo:o/r is:issue", SearchQuery.build("foo", "o/r", "me", false, SearchQuery.ISSUES));
        assertEquals("foo repo:o/r is:pr", SearchQuery.build("foo", "o/r", "me", false, SearchQuery.PULLS));
        assertEquals("foo user:me", SearchQuery.build("foo", "", "me", false, SearchQuery.MIXED));
    }

    @Test
    public void ownQualifierWins() {
        assertEquals("foo user:bar", SearchQuery.build("foo user:bar", "", "me", false, SearchQuery.CODE));
        assertEquals("foo", SearchQuery.build("foo", "", "", false, SearchQuery.CODE));
    }

    @Test
    public void repoFromApiUrl() {
        assertEquals("o/r", SearchQuery.repoFromApiUrl("https://api.github.com/repos/o/r"));
        assertEquals("o/r", SearchQuery.repoFromApiUrl("https://api.github.com/repos/o/r/issues/3"));
        assertEquals("", SearchQuery.repoFromApiUrl("x"));
        assertEquals("", SearchQuery.repoFromApiUrl(null));
    }
}
