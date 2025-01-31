package com.uid2.admin.audit;

import com.uid2.shared.auth.IAuthorizable;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class AuditMiddlewareImpl implements AuditMiddleware{
    private final AuditWriter auditWriter;

    public AuditMiddlewareImpl(AuditWriter writer){
        this.auditWriter = writer;
    }

    @Override
    public Handler<RoutingContext> handle(Function<RoutingContext, List<OperationModel>> handler) {
        InnerAuditHandler auditHandler = new InnerAuditHandler(handler, auditWriter);
        return auditHandler::writeLog;
    }

    private static class InnerAuditHandler{
        private final Function<RoutingContext, List<OperationModel>> innerHandler;
        private final AuditWriter auditWriter;
        private InnerAuditHandler(Function<RoutingContext, List<OperationModel>> handler, AuditWriter auditWriter) {
            this.innerHandler = handler;
            this.auditWriter = auditWriter;
        }

        public void writeLog(RoutingContext rc){
            List<OperationModel> modelList = innerHandler.apply(rc);
            if(modelList != null){
                String ipAddress = getIPAddress(rc);
                for(OperationModel model : modelList) {
                    AuditModel auditModel = new QLDBAuditModel(model.itemType, model.itemKey, model.actionTaken, ipAddress,
                            ((IAuthorizable) rc.data().get("api-client")).getContact(),
                            System.getenv("HOSTNAME"), Instant.now().getEpochSecond(), model.itemHash, model.summary);
                    auditWriter.writeLog(auditModel);
                }
            }
        }

        private static String getIPAddress(RoutingContext rc) {
            List<String> listIP = rc.request().headers().getAll("X-Forwarded-For");
            List<InetAddress> publicIPs = new ArrayList<>();
            for(String str : listIP){
                try {
                    InetAddress address = InetAddress.getByName(str);
                    if(!address.isSiteLocalAddress()){
                        publicIPs.add(address);
                    }
                }
                catch(UnknownHostException ignored){

                }

            }
            if(publicIPs.isEmpty()){
                return rc.request().remoteAddress().toString();
            }
            else{
                return publicIPs.get(0).getHostAddress(); //arbitrary if multiple
            }
        }
    }
}
