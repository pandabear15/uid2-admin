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

package com.uid2.admin.vertx.service;

import com.amazonaws.util.StringUtils;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.uid2.admin.Constants;
import com.uid2.admin.audit.*;
import com.uid2.admin.auth.AdminUser;
import com.uid2.admin.auth.AdminUserProvider;
import com.uid2.admin.secret.IKeyGenerator;
import com.uid2.admin.store.IStorageManager;
import com.uid2.admin.vertx.JsonUtil;
import com.uid2.admin.vertx.RequestUtil;
import com.uid2.admin.vertx.ResponseUtil;
import com.uid2.admin.vertx.WriteLock;
import com.uid2.shared.auth.Role;
import com.uid2.shared.middleware.AuthMiddleware;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.codec.digest.DigestUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class AdminKeyService implements IService {
    private final AuditMiddleware audit;
    private final AuthMiddleware auth;
    private final WriteLock writeLock;
    private final IStorageManager storageManager;
    private final AdminUserProvider adminUserProvider;
    private final IKeyGenerator keyGenerator;
    private final ObjectWriter jsonWriter = JsonUtil.createJsonWriter();
    private final String adminKeyPrefix;

    public AdminKeyService(JsonObject config,
                           AuditMiddleware audit,
                           AuthMiddleware auth,
                           WriteLock writeLock,
                           IStorageManager storageManager,
                           AdminUserProvider adminUserProvider,
                           IKeyGenerator keyGenerator) {
        this.audit = audit;
        this.auth = auth;
        this.writeLock = writeLock;
        this.storageManager = storageManager;
        this.adminUserProvider = adminUserProvider;
        this.keyGenerator = keyGenerator;

        this.adminKeyPrefix = config.getString("admin_key_prefix");
    }

    @Override
    public void setupRoutes(Router router) {

        router.get("/api/admin/metadata").handler(
                auth.handle(this::handleAdminMetadata, Role.ADMINISTRATOR));

        router.get("/api/admin/list").handler(
                auth.handle(audit.handle(this::handleAdminList), Role.ADMINISTRATOR));

        router.get("/api/admin/reveal").handler(
                auth.handle(audit.handle(this::handleAdminReveal), Role.ADMINISTRATOR));

        router.post("/api/admin/add").blockingHandler(auth.handle(audit.handle((ctx) -> {
            synchronized (writeLock) {
                return this.handleAdminAdd(ctx);
            }
        }), Role.ADMINISTRATOR));

        router.post("/api/admin/del").blockingHandler(auth.handle(audit.handle((ctx) -> {
            synchronized (writeLock) {
                return this.handleAdminDel(ctx);
            }
        }), Role.ADMINISTRATOR));

        router.post("/api/admin/disable").blockingHandler(auth.handle(audit.handle(ctx -> {
            synchronized (writeLock) {
                return this.handleAdminDisable(ctx);
            }
        }), Role.ADMINISTRATOR));

        router.post("/api/admin/enable").blockingHandler(auth.handle(audit.handle(ctx -> {
            synchronized (writeLock) {
                return this.handleAdminEnable(ctx);
            }
        }), Role.ADMINISTRATOR));

        router.post("/api/admin/rekey").blockingHandler(auth.handle(audit.handle(ctx -> {
            synchronized (writeLock) {
                return this.handleAdminRekey(ctx);
            }
        }), Role.ADMINISTRATOR));

        router.post("/api/admin/roles").blockingHandler(auth.handle(audit.handle(ctx -> {
            synchronized (writeLock) {
                return this.handleAdminRoles(ctx);
            }
        }), Role.ADMINISTRATOR));
    }

    private void handleAdminMetadata(RoutingContext rc) {
        try {
            rc.response()
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .end(adminUserProvider.getMetadata().encode());
        } catch (Exception e) {
            rc.fail(500, e);
        }
    }

    private List<OperationModel> handleAdminList(RoutingContext rc) {
        try {
            JsonArray ja = new JsonArray();
            Collection<AdminUser> collection = this.adminUserProvider.getAll();
            for (AdminUser a : collection) {
                ja.add(adminToJson(a));
            }

            rc.response()
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .end(ja.encode());
            return Collections.singletonList(new OperationModel(Type.ADMIN, Constants.DEFAULT_ITEM_KEY, Actions.LIST,
                    null, "list admins"));
        } catch (Exception e) {
            rc.fail(500, e);
            return null;
        }
    }

    private List<OperationModel> handleAdminReveal(RoutingContext rc) {
        try {
            AdminUser a = getAdminUser(rc.queryParam("name"));
            if (a == null) {
                ResponseUtil.error(rc, 404, "admin not found");
                return null;
            }

            rc.response()
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .end(jsonWriter.writeValueAsString(a));
            return Collections.singletonList(new OperationModel(Type.ADMIN, a.getName(), Actions.GET,
                    DigestUtils.sha256Hex(jsonWriter.writeValueAsString(a)), "revealed " + a.getName()));
        } catch (Exception e) {
            rc.fail(500, e);
            return null;
        }
    }

    private List<OperationModel> handleAdminAdd(RoutingContext rc) {
        try {
            // refresh manually
            adminUserProvider.loadContent(adminUserProvider.getMetadata());

            final String name = rc.queryParam("name").get(0);
            Optional<AdminUser> existingAdmin = this.adminUserProvider.getAll()
                    .stream().filter(a -> name.equals(a.getName()))
                    .findFirst();
            if (existingAdmin.isPresent()) {
                ResponseUtil.error(rc, 400, "admin existed");
                return null;
            }

            Set<Role> roles = RequestUtil.getRoles(rc.queryParam("roles").get(0));
            if (roles == null) {
                ResponseUtil.error(rc, 400, "incorrect or none roles specified");
                return null;
            }

            List<AdminUser> admins = this.adminUserProvider.getAll()
                    .stream().sorted((a, b) -> (int) (a.getCreated() - b.getCreated()))
                    .collect(Collectors.toList());

            // create a random key
            String key = keyGenerator.generateRandomKeyString(32);
            if (this.adminKeyPrefix != null) key = this.adminKeyPrefix + key;

            // create new admin
            long created = Instant.now().getEpochSecond();
            AdminUser newAdmin = new AdminUser(key, name, name, created, roles, false);

            // add admin to the array
            admins.add(newAdmin);

            // upload to storage
            storageManager.uploadAdminUsers(adminUserProvider, admins);

            // respond with new admin created
            rc.response().end(jsonWriter.writeValueAsString(newAdmin));
            return Collections.singletonList(new OperationModel(Type.ADMIN, name, Actions.CREATE,
                    DigestUtils.sha256Hex(jsonWriter.writeValueAsString(newAdmin)), "created " + name));
        } catch (Exception e) {
            rc.fail(500, e);
            return null;
        }
    }

    private List<OperationModel> handleAdminDel(RoutingContext rc) {
        try {
            // refresh manually
            adminUserProvider.loadContent(adminUserProvider.getMetadata());

            AdminUser a = getAdminUser(rc.queryParam("name"));
            if (a == null) {
                ResponseUtil.error(rc, 404, "admin not found");
                return null;
            }

            List<AdminUser> admins = this.adminUserProvider.getAll()
                    .stream().sorted((x, y) -> (int) (x.getCreated() - y.getCreated()))
                    .collect(Collectors.toList());

            // delete admin from the array
            admins.remove(a);

            // upload to storage
            storageManager.uploadAdminUsers(adminUserProvider, admins);

            // respond with admin deleted
            rc.response().end(jsonWriter.writeValueAsString(a));
            return Collections.singletonList(new OperationModel(Type.ADMIN, a.getName(), Actions.DELETE,
                    DigestUtils.sha256Hex(jsonWriter.writeValueAsString(a)), "deleted " + a.getName()));
        } catch (Exception e) {
            rc.fail(500, e);
            return null;
        }
    }

    private List<OperationModel> handleAdminDisable(RoutingContext rc) {
        return handleAdminDisable(rc, true);
    }

    private List<OperationModel> handleAdminEnable(RoutingContext rc) {
        return handleAdminDisable(rc, false);
    }

    private List<OperationModel> handleAdminDisable(RoutingContext rc, boolean disableFlag) {
        try {
            // refresh manually
            adminUserProvider.loadContent(adminUserProvider.getMetadata());

            AdminUser a = getAdminUser(rc.queryParam("name"));
            if (a == null) {
                ResponseUtil.error(rc, 404, "admin not found");
                return null;
            }

            List<AdminUser> admins = this.adminUserProvider.getAll()
                    .stream().sorted((x, y) -> (int) (x.getCreated() - y.getCreated()))
                    .collect(Collectors.toList());

            if (a.isDisabled() == disableFlag) {
                rc.fail(400, new Exception("no change needed"));
                return null;
            }

            a.setDisabled(disableFlag);

            JsonObject response = adminToJson(a);

            // upload to storage
            storageManager.uploadAdminUsers(adminUserProvider, admins);

            // respond with admin disabled/enabled
            rc.response().end(response.encode());
            return Collections.singletonList(new OperationModel(Type.ADMIN, a.getName(), disableFlag ? Actions.DISABLE : Actions.ENABLE,
                    DigestUtils.sha256Hex(jsonWriter.writeValueAsString(a)), (disableFlag ? "disabled " : "enabled ") + a.getName()));
        } catch (Exception e) {
            rc.fail(500, e);
            return null;
        }
    }

    private List<OperationModel> handleAdminRekey(RoutingContext rc) {
        try {
            // refresh manually
            adminUserProvider.loadContent(adminUserProvider.getMetadata());

            AdminUser a = getAdminUser(rc.queryParam("name"));
            if (a == null) {
                ResponseUtil.error(rc, 404, "admin not found");
                return null;
            }

            List<AdminUser> admins = this.adminUserProvider.getAll()
                    .stream().sorted((x, y) -> (int) (x.getCreated() - y.getCreated()))
                    .collect(Collectors.toList());

            String newKey = keyGenerator.generateRandomKeyString(32);
            if (this.adminKeyPrefix != null) newKey = this.adminKeyPrefix + newKey;
            a.setKey(newKey);

            // upload to storage
            storageManager.uploadAdminUsers(adminUserProvider, admins);

            // return admin with new key
            rc.response().end(jsonWriter.writeValueAsString(a));
            return Collections.singletonList(new OperationModel(Type.ADMIN, a.getName(), Actions.UPDATE,
                    DigestUtils.sha256Hex(adminToJson(a).toString()), "rekeyed " + a.getName()));
        } catch (Exception e) {
            rc.fail(500, e);
            return null;
        }
    }

    private List<OperationModel> handleAdminRoles(RoutingContext rc) {
        try {
            // refresh manually
            adminUserProvider.loadContent(adminUserProvider.getMetadata());

            AdminUser a = getAdminUser(rc.queryParam("name"));
            if (a == null) {
                ResponseUtil.error(rc, 404, "admin not found");
                return null;
            }

            Set<Role> roles = RequestUtil.getRoles(rc.queryParam("roles").get(0));
            if (roles == null) {
                ResponseUtil.error(rc, 400, "incorrect or none roles specified");
                return null;
            }

            List<AdminUser> admins = this.adminUserProvider.getAll()
                    .stream().sorted((x, y) -> (int) (x.getCreated() - y.getCreated()))
                    .collect(Collectors.toList());

            a.setRoles(roles);

            // upload to storage
            storageManager.uploadAdminUsers(adminUserProvider, admins);

            // return client with new key
            rc.response().end(jsonWriter.writeValueAsString(a));
            List<String> stringRoleList = new ArrayList<>();
            for (Role role : roles) {
                stringRoleList.add(role.toString());
            }
            return Collections.singletonList(new OperationModel(Type.ADMIN, a.getName(), Actions.UPDATE,
                    DigestUtils.sha256Hex(adminToJson(a).toString()), "set roles of " + a.getName() +
                    " to {" + StringUtils.join(",", stringRoleList.toArray(new String[0])) + "}"));
        } catch (Exception e) {
            rc.fail(500, e);
            return null;
        }
    }

    /**
     * Writes an AdminUser to Json format, leaving out the sensitive key field.
     * @param a the AdminUser to write
     * @return a JsonObject representing a, without the key field.
     */
    public JsonObject adminToJson(AdminUser a){
        JsonObject jo = new JsonObject();

        jo.put("name", a.getName());
        jo.put("contact", a.getContact());
        jo.put("roles", RequestUtil.getRolesSpec(a.getRoles()));
        jo.put("created", a.getCreated());
        jo.put("disabled", a.isDisabled());

        return jo;
    }

    /**
     * Returns an arbitrary admin with the same name as the first item in the passed list.
     * @param names a list of names of admins
     * @return an arbitrary admin with the same name as names.get(0), or null if names is empty
     * or no admin with the specified name exists.
     */
    private AdminUser getAdminUser(List<String> names){
        if(names.isEmpty()){
            return null;
        }
        final String name = names.get(0);
        Optional<AdminUser> existingAdmin = this.adminUserProvider.getAll()
                .stream().filter(a -> name.equals(a.getName()))
                .findFirst();
        return existingAdmin.orElse(null);
    }
}
