package com.ghmanager.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiEtagsTest {

    @Before
    public void setUp() {
        ApiEtags.clear();
    }

    @After
    public void tearDown() {
        ApiEtags.clear();
    }

    private static String[] value(int i) {
        return new String[]{"etag" + i, "body" + i};
    }

    @Test
    public void getMissingReturnsNull() {
        assertNull(ApiEtags.get("missing"));
    }

    @Test
    public void putThenGetReturnsSameArray() {
        String[] v = value(1);
        ApiEtags.put("k", v);
        assertSame(v, ApiEtags.get("k"));
    }

    @Test
    public void putOverwritesExistingKey() {
        ApiEtags.put("k", value(1));
        String[] second = value(2);
        ApiEtags.put("k", second);
        assertSame(second, ApiEtags.get("k"));
    }

    @Test
    public void clearRemovesEverything() {
        ApiEtags.put("a", value(1));
        ApiEtags.put("b", value(2));
        ApiEtags.clear();
        assertNull(ApiEtags.get("a"));
        assertNull(ApiEtags.get("b"));
    }

    @Test
    public void keepsExactlyEightyEntriesAndDropsOldest() {
        String[][] values = new String[100][];
        for (int i = 0; i < 100; i++) {
            values[i] = value(i);
            ApiEtags.put("key" + i, values[i]);
        }
        for (int i = 0; i < 20; i++) assertNull("key" + i, ApiEtags.get("key" + i));
        for (int i = 20; i < 100; i++) assertSame("key" + i, values[i], ApiEtags.get("key" + i));
    }

    @Test
    public void eightyEntriesAreAllKept() {
        for (int i = 0; i < 80; i++) ApiEtags.put("key" + i, value(i));
        for (int i = 0; i < 80; i++) assertNotNull("key" + i, ApiEtags.get("key" + i));
    }

    @Test
    public void withoutReadsTheFirstKeyIsEvicted() {
        for (int i = 0; i < 81; i++) ApiEtags.put("key" + i, value(i));
        assertNull(ApiEtags.get("key0"));
        assertNotNull(ApiEtags.get("key1"));
        assertNotNull(ApiEtags.get("key80"));
    }

    @Test
    public void getRefreshesEntryAndNextOldestIsEvicted() {
        for (int i = 0; i < 80; i++) ApiEtags.put("key" + i, value(i));
        assertNotNull(ApiEtags.get("key0"));
        ApiEtags.put("extra", value(999));
        assertNotNull(ApiEtags.get("key0"));
        assertNull(ApiEtags.get("key1"));
        assertNotNull(ApiEtags.get("key2"));
        assertNotNull(ApiEtags.get("extra"));
    }

    @Test
    public void overwriteDoesNotGrowCache() {
        for (int i = 0; i < 80; i++) ApiEtags.put("key" + i, value(i));
        ApiEtags.put("key40", value(1000));
        ApiEtags.put("key40", value(1001));
        for (int i = 0; i < 80; i++) assertNotNull("key" + i, ApiEtags.get("key" + i));
    }
}
