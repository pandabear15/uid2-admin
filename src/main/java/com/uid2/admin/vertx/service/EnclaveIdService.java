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

import com.fasterxml.jackson.databind.ObjectWriter;
import com.uid2.admin.Constants;
import com.uid2.admin.audit.Actions;
import com.uid2.admin.audit.IAuditMiddleware;
import com.uid2.admin.audit.OperationModel;
import com.uid2.admin.audit.Type;
import com.uid2.admin.store.IStorageManager;
import com.uid2.admin.vertx.JsonUtil;
import com.uid2.admin.vertx.RequestUtil;
import com.uid2.admin.vertx.ResponseUtil;
import com.uid2.admin.vertx.WriteLock;
import com.uid2.shared.auth.EnclaveIdentifierProvider;
import com.uid2.shared.auth.Role;
import com.uid2.shared.middleware.AuthMiddleware;
import com.uid2.shared.model.EnclaveIdentifier;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.codec.digest.DigestUtils;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EnclaveIdService implements IService {
    private final IAuditMiddleware audit;
    private final AuthMiddleware auth;
    private final WriteLock writeLock;
    private final IStorageManager storageManager;
    private final EnclaveIdentifierProvider enclaveIdProvider;
    private final ObjectWriter jsonWriter = JsonUtil.createJsonWriter();

    public EnclaveIdService(IAuditMiddleware audit,
                            AuthMiddleware auth,
                            WriteLock writeLock,
                            IStorageManager storageManager,
                            EnclaveIdentifierProvider enclaveIdProvider) {
        this.audit = audit;
        this.auth = auth;
        this.writeLock = writeLock;
        this.storageManager = storageManager;
        this.enclaveIdProvider = enclaveIdProvider;
    }

    @Override
    public void setupRoutes(Router router) {
        router.get("/api/enclave/metadata").handler(
                auth.handle(this::handleEnclaveMetadata, Role.OPERATOR_MANAGER));
        router.get("/api/enclave/list").handler(
                auth.handle(ctx -> this.handleEnclaveList(ctx, audit.handle(ctx)), Role.OPERATOR_MANAGER));

        router.post("/api/enclave/add").blockingHandler(auth.handle(ctx -> {
            synchronized (writeLock) {
                this.handleEnclaveAdd(ctx, audit.handle(ctx));
            }
        }, Role.OPERATOR_MANAGER));
        router.post("/api/enclave/del").blockingHandler(auth.handle(ctx -> {
            synchronized (writeLock) {
                this.handleEnclaveDel(ctx, audit.handle(ctx));
            }
        }, Role.ADMINISTRATOR));
    }

    @Override
    public Collection<OperationModel> qldbSetup(){
        try {
            Collection<EnclaveIdentifier> enclaves = enclaveIdProvider.getAll();
            Collection<OperationModel> newModels = new HashSet<>();
            for (EnclaveIdentifier e : enclaves) {
                newModels.add(new OperationModel(Type.ENCLAVE, e.getName(), null,
                        DigestUtils.sha256Hex(jsonWriter.writeValueAsString(e)), null));
            }
            return newModels;
        } catch (Exception e) {
            e.printStackTrace();
            return new HashSet<>();
        }
    }

    @Override
    public Type tableType(){
        return Type.ENCLAVE;
    }

    private void handleEnclaveMetadata(RoutingContext rc) {
        try {
            rc.response()
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .end(enclaveIdProvider.getMetadata().encode());
        } catch (Exception e) {
            rc.fail(500, e);
        }
    }

    private void handleEnclaveList(RoutingContext rc, Function<List<OperationModel>, Boolean> fxn) {
        try {
            JsonArray ja = new JsonArray();
            Collection<EnclaveIdentifier> collection = this.enclaveIdProvider.getAll();
            for (EnclaveIdentifier e : collection) {
                JsonObject jo = new JsonObject();
                ja.add(jo);

                jo.put("name", e.getName());
                jo.put("protocol", e.getProtocol());
                jo.put("identifier", e.getIdentifier());
                jo.put("created", e.getCreated());
            }

            List<OperationModel> modelList = Collections.singletonList(new OperationModel(Type.ENCLAVE, Constants.DEFAULT_ITEM_KEY,
                    Actions.LIST, null, "list enclaves"));
            if(!fxn.apply(modelList)){
                ResponseUtil.error(rc, 500, "failed");
                return;
            }

            rc.response()
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .end(jsonWriter.writeValueAsString(collection));
        } catch (Exception e) {
            rc.fail(500, e);
        }
    }

    private void handleEnclaveAdd(RoutingContext rc, Function<List<OperationModel>, Boolean> fxn) {
        try {
            // refresh manually
            enclaveIdProvider.loadContent(enclaveIdProvider.getMetadata());

            final String name = rc.queryParam("name").get(0);
            Optional<EnclaveIdentifier> existingEnclaveId = this.enclaveIdProvider.getAll()
                    .stream().filter(e -> e.getName().equals(name))
                    .findFirst();
            if (existingEnclaveId.isPresent()) {
                ResponseUtil.error(rc, 400, "enclave existed");
                return;
            }

            String protocol = RequestUtil.validateOperatorProtocol(rc.queryParam("protocol").get(0));
            if (protocol == null) {
                ResponseUtil.error(rc, 400, "no protocol specified");
                return;
            }

            final String enclaveId = rc.queryParam("enclave_id").get(0);
            if (enclaveId == null) {
                ResponseUtil.error(rc, 400, "enclave_id not specified");
                return;
            }

            List<EnclaveIdentifier> enclaveIds = this.enclaveIdProvider.getAll()
                    .stream().sorted((a, b) -> (int) (a.getCreated() - b.getCreated()))
                    .collect(Collectors.toList());

            // create new enclave id
            long created = Instant.now().getEpochSecond();
            EnclaveIdentifier newEnclaveId = new EnclaveIdentifier(name, protocol, enclaveId, created);

            // add enclave id to the array
            enclaveIds.add(newEnclaveId);

            List<OperationModel> modelList = Collections.singletonList(new OperationModel(Type.ENCLAVE, name, Actions.CREATE,
                    DigestUtils.sha256Hex(jsonWriter.writeValueAsString(newEnclaveId)), "created " + name));
            if(!fxn.apply(modelList)){
                ResponseUtil.error(rc, 500, "failed");
                return;
            }

            // upload to storage
            storageManager.uploadEnclaveIds(enclaveIdProvider, enclaveIds);

            // respond with new enclave id
            rc.response().end(jsonWriter.writeValueAsString(newEnclaveId));
        } catch (Exception e) {
            rc.fail(500, e);
        }
    }

    private void handleEnclaveDel(RoutingContext rc, Function<List<OperationModel>, Boolean> fxn) {
        try {
            // refresh manually
            enclaveIdProvider.loadContent(enclaveIdProvider.getMetadata());

            final String name = rc.queryParam("name").get(0);
            Optional<EnclaveIdentifier> existingEnclaveId = this.enclaveIdProvider.getAll()
                    .stream().filter(e -> e.getName().equals(name))
                    .findFirst();
            if (!existingEnclaveId.isPresent()) {
                ResponseUtil.error(rc, 404, "enclave id not found");
                return;
            }

            List<EnclaveIdentifier> enclaveIds = this.enclaveIdProvider.getAll()
                    .stream().sorted((a, b) -> (int) (a.getCreated() - b.getCreated()))
                    .collect(Collectors.toList());

            // delete client from the array
            EnclaveIdentifier e = existingEnclaveId.get();
            enclaveIds.remove(e);

            List<OperationModel> modelList = Collections.singletonList(new OperationModel(Type.ENCLAVE, e.getName(), Actions.DELETE,
                    DigestUtils.sha256Hex(jsonWriter.writeValueAsString(e)), "deleted " + e.getName()));
            if(!fxn.apply(modelList)){
                ResponseUtil.error(rc, 500, "failed");
                return;
            }

            // upload to storage
            storageManager.uploadEnclaveIds(enclaveIdProvider, enclaveIds);

            // respond with the deleted enclave id
            rc.response().end(jsonWriter.writeValueAsString(e));
        } catch (Exception e) {
            rc.fail(500, e);
        }
    }
}
