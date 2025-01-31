// Copyright (c) 2021 The Trade Desk, Inc
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice,
//    this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package com.uid2.admin.vertx;

import com.uid2.admin.vertx.service.EncryptionKeyService;
import com.uid2.admin.vertx.service.IService;
import com.uid2.admin.vertx.test.ServiceTestBase;
import com.uid2.shared.auth.Role;
import com.uid2.shared.model.EncryptionKey;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class EncryptionKeyServiceTest extends ServiceTestBase {
    private static final int MASTER_KEY_ACTIVATES_IN_SECONDS = 3600;
    private static final int MASTER_KEY_EXPIRES_AFTER_SECONDS = 7200;
    private static final int SITE_KEY_ACTIVATES_IN_SECONDS = 36000;
    private static final int SITE_KEY_EXPIRES_AFTER_SECONDS = 72000;

    private EncryptionKeyService keyService = null;

    @Override
    protected IService createService() {
        this.config.put("master_key_activates_in_seconds", MASTER_KEY_ACTIVATES_IN_SECONDS);
        this.config.put("master_key_expires_after_seconds", MASTER_KEY_EXPIRES_AFTER_SECONDS);
        this.config.put("site_key_activates_in_seconds", SITE_KEY_ACTIVATES_IN_SECONDS);
        this.config.put("site_key_expires_after_seconds", SITE_KEY_EXPIRES_AFTER_SECONDS);

        keyService = new EncryptionKeyService(config, audit, auth, writeLock, storageManager, keyProvider, keyGenerator);
        return keyService;
    }

    private void assertKeyActivation(Instant generatedTime, int activatesIn, int expiresAfter,
                                     Instant actualCreated, Instant actualActivates, Instant actualExpires) {
        assertTrue(generatedTime.plusSeconds(-5).isBefore(actualCreated));
        assertTrue(generatedTime.plusSeconds(5).isAfter(actualCreated));
        assertEquals(actualCreated.plusSeconds(activatesIn), actualActivates);
        assertEquals(actualActivates.plusSeconds(expiresAfter), actualExpires);
    }

    private void assertSiteKeyActivation(EncryptionKey key, Instant generatedTime) {
        assertKeyActivation(generatedTime, SITE_KEY_ACTIVATES_IN_SECONDS, SITE_KEY_EXPIRES_AFTER_SECONDS,
                key.getCreated(), key.getActivates(), key.getExpires());
    }

    private void checkEncryptionKeyResponse(EncryptionKey[] expectedKeys, Object[] actualKeys) {
        assertEquals(expectedKeys.length, actualKeys.length);
        for (int i = 0; i < expectedKeys.length; ++i) {
            final EncryptionKey expectedKey = expectedKeys[i];
            final JsonObject actualKey = (JsonObject) actualKeys[i];
            assertEquals(expectedKey.getId(), actualKey.getInteger("id"));
            assertEquals(expectedKey.getCreated(), Instant.ofEpochMilli(actualKey.getLong("created")));
            assertEquals(expectedKey.getActivates(), Instant.ofEpochMilli(actualKey.getLong("activates")));
            assertEquals(expectedKey.getExpires(), Instant.ofEpochMilli(actualKey.getLong("expires")));
            assertEquals(expectedKey.getSiteId(), actualKey.getInteger("site_id"));
            assertFalse(actualKey.containsKey("secret"));
        }
    }

    private void checkRotatedKeyResponse(int startingKeyId, int[] expectedSiteIds, int activatesIn, int expiresAfter, Object[] actualKeys) {
        assertEquals(expectedSiteIds.length, actualKeys.length);
        final Set<Integer> actualSiteIds = new HashSet<>();
        for (int i = 0; i < actualKeys.length; ++i) {
            final int expectedKeyId = startingKeyId + i;
            final JsonObject actualKey = (JsonObject) actualKeys[i];
            assertEquals(expectedKeyId, actualKey.getInteger("id"));
            assertKeyActivation(Instant.now(), activatesIn, expiresAfter,
                    Instant.ofEpochMilli(actualKey.getLong("created")),
                    Instant.ofEpochMilli(actualKey.getLong("activates")),
                    Instant.ofEpochMilli(actualKey.getLong("expires")));
            actualSiteIds.add(actualKey.getInteger("site_id"));
            assertFalse(actualKey.containsKey("secret"));
        }
        for (int expectedSiteId : expectedSiteIds) {
            assertTrue(actualSiteIds.contains(expectedSiteId));
        }
    }

    @Test
    void addSiteKey() throws Exception {
        setEncryptionKeys(123);
        final EncryptionKey key = keyService.addSiteKey(5);
        verify(storageManager).uploadEncryptionKeys(any(), collectionOfSize(1), eq(124));
        assertSiteKeyActivation(key, Instant.now());
    }

    @Test
    void listKeysNoKeys(Vertx vertx, VertxTestContext testContext) {
        fakeAuth(Role.SECRET_MANAGER);

        get(vertx, "api/key/list", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            assertEquals(0, response.bodyAsJsonArray().size());
            testContext.completeNow();
        });
    }

    @Test
    void listKeysWithKeys(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), 5),
                new EncryptionKey(12, null, Instant.ofEpochMilli(10012), Instant.ofEpochMilli(20012), Instant.ofEpochMilli(30012), 5),
                new EncryptionKey(13, null, Instant.ofEpochMilli(10013), Instant.ofEpochMilli(20013), Instant.ofEpochMilli(30013), 6),
                new EncryptionKey(14, null, Instant.ofEpochMilli(10014), Instant.ofEpochMilli(20014), Instant.ofEpochMilli(30014), 7),
        };
        setEncryptionKeys(777, keys);

        get(vertx, "api/key/list", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            checkEncryptionKeyResponse(keys, response.bodyAsJsonArray().stream().toArray());
            testContext.completeNow();
        });
    }

    @Test
    void rotateMasterKey(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), -1),
                new EncryptionKey(12, null, Instant.ofEpochMilli(10012), Instant.ofEpochMilli(20012), Instant.ofEpochMilli(30012), 5),
                new EncryptionKey(13, null, Instant.ofEpochMilli(10013), Instant.ofEpochMilli(20012), Instant.ofEpochMilli(30012), -2),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_master?min_age_seconds=100", "", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            checkRotatedKeyResponse(777+1, new int[] { -1, -2 },
                    MASTER_KEY_ACTIVATES_IN_SECONDS, MASTER_KEY_EXPIRES_AFTER_SECONDS,
                    response.bodyAsJsonArray().stream().toArray());
            try {
                verify(storageManager).uploadEncryptionKeys(any(), collectionOfSize(keys.length+2), eq(777+2));
            } catch (Exception ex) {
                fail(ex);
            }
            testContext.completeNow();
        });
    }

    @Test
    void rotateMasterKeyNewEnough(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), -1),
                new EncryptionKey(12, null, Instant.ofEpochMilli(10012), Instant.now().plusSeconds(MASTER_KEY_ACTIVATES_IN_SECONDS + 1000), Instant.MAX, -1),
                new EncryptionKey(13, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), -2),
                new EncryptionKey(14, null, Instant.ofEpochMilli(10012), Instant.now().plusSeconds(MASTER_KEY_ACTIVATES_IN_SECONDS + 1000), Instant.MAX, -2),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_master?min_age_seconds=100", "", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            assertEquals(0, response.bodyAsJsonArray().size());
            try {
                verify(storageManager, times(0)).uploadEncryptionKeys(any(), any(), anyInt());
            } catch (Exception ex) {
                fail(ex);
            }
            testContext.completeNow();
        });
    }

    @Test
    void rotateMasterKeyNewEnoughWithForce(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), -1),
                new EncryptionKey(12, null, Instant.ofEpochMilli(10012), Instant.now().plusSeconds(MASTER_KEY_ACTIVATES_IN_SECONDS + 1000), Instant.MAX, -1),
                new EncryptionKey(13, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), -2),
                new EncryptionKey(14, null, Instant.ofEpochMilli(10012), Instant.now().plusSeconds(MASTER_KEY_ACTIVATES_IN_SECONDS + 1000), Instant.MAX, -2),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_master?min_age_seconds=100&force=true", "", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            checkRotatedKeyResponse(777+1, new int[] { -1, -2 },
                    MASTER_KEY_ACTIVATES_IN_SECONDS, MASTER_KEY_EXPIRES_AFTER_SECONDS,
                    response.bodyAsJsonArray().stream().toArray());
            try {
                verify(storageManager).uploadEncryptionKeys(any(), collectionOfSize(keys.length+2), eq(777+2));
            } catch (Exception ex) {
                fail(ex);
            }
            testContext.completeNow();
        });
    }

    @Test
    void rotateSiteKey(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), -1),
                new EncryptionKey(12, null, Instant.ofEpochMilli(10012), Instant.ofEpochMilli(20012), Instant.ofEpochMilli(30012), 5),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_site?site_id=5&min_age_seconds=100", "", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            checkRotatedKeyResponse(777+1, new int[] { 5 },
                    SITE_KEY_ACTIVATES_IN_SECONDS, SITE_KEY_EXPIRES_AFTER_SECONDS,
                    response.bodyAsJsonArray().stream().toArray());
            try {
                verify(storageManager).uploadEncryptionKeys(any(), collectionOfSize(keys.length+1), eq(777+1));
            } catch (Exception ex) {
                fail(ex);
            }
            testContext.completeNow();
        });
    }

    @Test
    void rotateSiteKeyNewEnough(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), 5),
                new EncryptionKey(12, null, Instant.ofEpochMilli(10012), Instant.now().plusSeconds(SITE_KEY_ACTIVATES_IN_SECONDS + 100), Instant.MAX, 5),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_site?site_id=5&min_age_seconds=100", "", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            assertEquals(0, response.bodyAsJsonArray().size());
            try {
                verify(storageManager, times(0)).uploadEncryptionKeys(any(), any(), anyInt());
            } catch (Exception ex) {
                fail(ex);
            }
            testContext.completeNow();
        });
    }

    @Test
    void rotateSiteKeyNewEnoughWithForce(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), 5),
                new EncryptionKey(12, null, Instant.ofEpochMilli(10012), Instant.now().plusSeconds(SITE_KEY_ACTIVATES_IN_SECONDS + 100), Instant.MAX, 5),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_site?site_id=5&min_age_seconds=100&force=true", "", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            checkRotatedKeyResponse(777+1, new int[] { 5 },
                    SITE_KEY_ACTIVATES_IN_SECONDS, SITE_KEY_EXPIRES_AFTER_SECONDS,
                    response.bodyAsJsonArray().stream().toArray());
            try {
                verify(storageManager).uploadEncryptionKeys(any(), collectionOfSize(keys.length+1), eq(777+1));
            } catch (Exception ex) {
                fail(ex);
            }
            testContext.completeNow();
        });
    }

    @Test
    void rotateSiteKeyNoSiteKey(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        setEncryptionKeys(777);

        post(vertx, "api/key/rotate_site?site_id=5&min_age_seconds=100", "", expectHttpError(testContext, 404));
        verify(storageManager, times(0)).uploadEncryptionKeys(any(), any(), anyInt());
    }

    @Test
    void rotateSiteKeyMasterSite(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), -1),
                new EncryptionKey(12, null, Instant.ofEpochMilli(10012), Instant.ofEpochMilli(20012), Instant.ofEpochMilli(30012), 5),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_site?site_id=-1&min_age_seconds=100", "", expectHttpError(testContext, 400));
        verify(storageManager, times(0)).uploadEncryptionKeys(any(), any(), anyInt());
    }

    @Test
    void rotateSiteKeySpecialSite1(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), 1),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_site?site_id=-1&min_age_seconds=100", "", expectHttpError(testContext, 400));
        verify(storageManager, times(0)).uploadEncryptionKeys(any(), any(), anyInt());
    }

    @Test
    void rotateSiteKeySpecialSite2(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), 2),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_site?site_id=2&min_age_seconds=100", "", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            checkRotatedKeyResponse(777+1, new int[] { 2 },
                    SITE_KEY_ACTIVATES_IN_SECONDS, SITE_KEY_EXPIRES_AFTER_SECONDS,
                    response.bodyAsJsonArray().stream().toArray());
            try {
                verify(storageManager).uploadEncryptionKeys(any(), collectionOfSize(keys.length+1), eq(777+1));
            } catch (Exception ex) {
                fail(ex);
            }
            testContext.completeNow();
        });
    }

    @Test
    void rotateAllSiteKeys(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(10, null, Instant.ofEpochMilli(10010), Instant.ofEpochMilli(20010), Instant.ofEpochMilli(30010), -1),
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), 5),
                new EncryptionKey(12, null, Instant.ofEpochMilli(10012), Instant.MAX, Instant.MAX, 5),
                new EncryptionKey(13, null, Instant.ofEpochMilli(10013), Instant.ofEpochMilli(20013), Instant.ofEpochMilli(30013), 6),
                new EncryptionKey(14, null, Instant.ofEpochMilli(10014), Instant.ofEpochMilli(20014), Instant.ofEpochMilli(30014), 7),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_all_sites?site_id=5&min_age_seconds=100", "", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            checkRotatedKeyResponse(777+1, new int[] { 6, 7 },
                    SITE_KEY_ACTIVATES_IN_SECONDS, SITE_KEY_EXPIRES_AFTER_SECONDS,
                    response.bodyAsJsonArray().stream().toArray());
            try {
                verify(storageManager).uploadEncryptionKeys(any(), collectionOfSize(keys.length+2), eq(777+2));
            } catch (Exception ex) {
                fail(ex);
            }
            testContext.completeNow();
        });
    }

    @Test
    void rotateAllSiteKeysWithForce(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(10, null, Instant.ofEpochMilli(10010), Instant.ofEpochMilli(20010), Instant.ofEpochMilli(30010), -1),
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), 5),
                new EncryptionKey(12, null, Instant.ofEpochMilli(10012), Instant.MAX, Instant.MAX, 5),
                new EncryptionKey(13, null, Instant.ofEpochMilli(10013), Instant.ofEpochMilli(20013), Instant.ofEpochMilli(30013), 6),
                new EncryptionKey(14, null, Instant.ofEpochMilli(10014), Instant.ofEpochMilli(20014), Instant.ofEpochMilli(30014), 7),
                new EncryptionKey(15, null, Instant.ofEpochMilli(10015), Instant.ofEpochMilli(20015), Instant.ofEpochMilli(30015), 2),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_all_sites?site_id=5&min_age_seconds=100&force=true", "", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            checkRotatedKeyResponse(777+1, new int[] { 2, 5, 6, 7 },
                    SITE_KEY_ACTIVATES_IN_SECONDS, SITE_KEY_EXPIRES_AFTER_SECONDS,
                    response.bodyAsJsonArray().stream().toArray());
            try {
                verify(storageManager).uploadEncryptionKeys(any(), collectionOfSize(keys.length+4), eq(777+4));
            } catch (Exception ex) {
                fail(ex);
            }
            testContext.completeNow();
        });
    }

    @Test
    void rotateAllSiteKeysAllUpToDate(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        final EncryptionKey[] keys = {
                new EncryptionKey(11, null, Instant.ofEpochMilli(10011), Instant.ofEpochMilli(20011), Instant.ofEpochMilli(30011), 5),
                new EncryptionKey(12, null, Instant.ofEpochMilli(10012), Instant.MAX, Instant.MAX, 5),
        };
        setEncryptionKeys(777, keys);

        post(vertx, "api/key/rotate_all_sites?site_id=5&min_age_seconds=100", "", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            assertEquals(0, response.bodyAsJsonArray().size());
            try {
                verify(storageManager, times(0)).uploadEncryptionKeys(any(), any(), anyInt());
            } catch (Exception ex) {
                fail(ex);
            }
            testContext.completeNow();
        });
    }

    @Test
    void rotateAllSiteKeysNoSiteKeys(Vertx vertx, VertxTestContext testContext) throws Exception {
        fakeAuth(Role.SECRET_MANAGER);

        setEncryptionKeys(777);

        post(vertx, "api/key/rotate_all_sites?site_id=5&min_age_seconds=100", "", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            assertEquals(0, response.bodyAsJsonArray().size());
            try {
                verify(storageManager, times(0)).uploadEncryptionKeys(any(), any(), anyInt());
            } catch (Exception ex) {
                fail(ex);
            }
            testContext.completeNow();
        });
    }
}
