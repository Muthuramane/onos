/*
 * Copyright 2017-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.restconf.restconfmanager;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.glassfish.jersey.server.ChunkedOutput;
import org.onosproject.config.DynamicConfigService;
import org.onosproject.config.FailedException;
import org.onosproject.config.Filter;
import org.onosproject.restconf.api.RestconfException;
import org.onosproject.restconf.api.RestconfRpcOutput;
import org.onosproject.restconf.api.RestconfService;
import org.onosproject.restconf.utils.RestconfUtils;
import org.onosproject.yang.model.DataNode;
import org.onosproject.yang.model.DefaultResourceData;
import org.onosproject.yang.model.InnerNode;
import org.onosproject.yang.model.KeyLeaf;
import org.onosproject.yang.model.ListKey;
import org.onosproject.yang.model.NodeKey;
import org.onosproject.yang.model.ResourceData;
import org.onosproject.yang.model.ResourceId;
import org.onosproject.yang.model.RpcInput;
import org.onosproject.yang.model.RpcOutput;
import org.onosproject.yang.model.SchemaId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.onosproject.restconf.utils.RestconfUtils.convertDataNodeToJson;
import static org.onosproject.restconf.utils.RestconfUtils.convertJsonToDataNode;
import static org.onosproject.restconf.utils.RestconfUtils.convertUriToRid;
import static org.onosproject.restconf.utils.RestconfUtils.rmLastPathSegment;
import static org.onosproject.yang.model.DataNode.Type.MULTI_INSTANCE_NODE;
import static org.onosproject.yang.model.DataNode.Type.SINGLE_INSTANCE_LEAF_VALUE_NODE;
import static org.onosproject.yang.model.DataNode.Type.SINGLE_INSTANCE_NODE;

/*
 * ONOS RESTCONF application. The RESTCONF Manager
 * implements the main logic of the RESTCONF application.
 *
 * The design of the RESTCONF subsystem contains 2 major bundles:
 *    This bundle module is the back-end of the server.
 *    It provides the main logic of the RESTCONF server. It interacts with
 *    the Dynamic Config Service and yang runtime service to run operations
 *    on the YANG data objects (i.e., resource id, yang data node).
 */

@Component(immediate = true)
@Service
public class RestconfManager implements RestconfService {

    private static final String RESTCONF_ROOT = "/onos/restconf";

    private final int maxNumOfWorkerThreads = 5;

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DynamicConfigService dynamicConfigService;

    private ExecutorService workerThreadPool;

    @Activate
    protected void activate() {
        workerThreadPool = Executors
                .newFixedThreadPool(maxNumOfWorkerThreads,
                                    new ThreadFactoryBuilder()
                                            .setNameFormat("restconf-worker")
                                            .build());
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        workerThreadPool.shutdownNow();
        log.info("Stopped");
    }

    @Override
    public ObjectNode runGetOperationOnDataResource(URI uri)
            throws RestconfException {
        ResourceId rid = convertUriToRid(uri);
        // TODO: define Filter (if there is any requirement).
        Filter filter = new Filter();
        DataNode dataNode;

        try {
            if (!dynamicConfigService.nodeExist(rid)) {
                return null;
            }
            dataNode = dynamicConfigService.readNode(rid, filter);
        } catch (FailedException e) {
            log.error("ERROR: DynamicConfigService: ", e);
            throw new RestconfException("ERROR: DynamicConfigService",
                                        INTERNAL_SERVER_ERROR);
        }
        ObjectNode rootNode = convertDataNodeToJson(rid, dataNode);
        return rootNode;
    }

    @Override
    public void runPostOperationOnDataResource(URI uri, ObjectNode rootNode)
            throws RestconfException {
        ResourceData receivedData = convertJsonToDataNode(uri, rootNode);
        ResourceId rid = receivedData.resourceId();
        List<DataNode> dataNodeList = receivedData.dataNodes();
        if (dataNodeList.size() > 1) {
            log.warn("There are more than one Data Node can be proceed: {}", dataNodeList.size());
        }
        DataNode dataNode = dataNodeList.get(0);

        if (rid == null) {
            rid = ResourceId.builder().addBranchPointSchema("/", null).build();
            dataNode = removeTopNode(dataNode);
        }

        try {
            dynamicConfigService.createNode(rid, dataNode);
        } catch (FailedException e) {
            log.error("ERROR: DynamicConfigService: ", e);
            throw new RestconfException("ERROR: DynamicConfigService",
                                        INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void runPutOperationOnDataResource(URI uri, ObjectNode rootNode)
            throws RestconfException {
        ResourceId rid = convertUriToRid(uri);
        ResourceData receivedData = convertJsonToDataNode(rmLastPathSegment(uri), rootNode);
        ResourceId parentRid = receivedData.resourceId();
        List<DataNode> dataNodeList = receivedData.dataNodes();
        if (dataNodeList.size() > 1) {
            log.warn("There are more than one Data Node can be proceed: {}", dataNodeList.size());
        }
        DataNode dataNode = dataNodeList.get(0);

        try {
            /*
             * If the data node already exists, then replace it.
             * Otherwise, create it.
             */
            if (dynamicConfigService.nodeExist(rid)) {
                dynamicConfigService.replaceNode(parentRid, dataNode);
            } else {
                dynamicConfigService.createNode(parentRid, dataNode);
            }

        } catch (FailedException e) {
            log.error("ERROR: DynamicConfigService: ", e);
            throw new RestconfException("ERROR: DynamicConfigService",
                                        INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void runDeleteOperationOnDataResource(URI uri)
            throws RestconfException {
        ResourceId rid = convertUriToRid(uri);
        try {
            if (dynamicConfigService.nodeExist(rid)) {
                dynamicConfigService.deleteNode(rid);
            }
        } catch (FailedException e) {
            log.error("ERROR: DynamicConfigService: ", e);
            throw new RestconfException("ERROR: DynamicConfigService",
                                        INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void runPatchOperationOnDataResource(URI uri, ObjectNode rootNode)
            throws RestconfException {
        ResourceData receivedData = convertJsonToDataNode(rmLastPathSegment(uri), rootNode);
        ResourceId rid = receivedData.resourceId();
        List<DataNode> dataNodeList = receivedData.dataNodes();
        if (dataNodeList.size() > 1) {
            log.warn("There are more than one Data Node can be proceed: {}", dataNodeList.size());
        }
        DataNode dataNode = dataNodeList.get(0);

        if (rid == null) {
            rid = ResourceId.builder().addBranchPointSchema("/", null).build();
            dataNode = removeTopNode(dataNode);
        }

        try {
            dynamicConfigService.updateNode(rid, dataNode);
        } catch (FailedException e) {
            log.error("ERROR: DynamicConfigService: ", e);
            throw new RestconfException("ERROR: DynamicConfigService",
                                        INTERNAL_SERVER_ERROR);
        }
    }

    private DataNode removeTopNode(DataNode dataNode) {
        if (dataNode instanceof InnerNode && dataNode.key().schemaId().name().equals("/")) {
            Map.Entry<NodeKey, DataNode> entry = ((InnerNode) dataNode).childNodes().entrySet().iterator().next();
            dataNode = entry.getValue();
        }
        return dataNode;
    }

    @Override
    public String getRestconfRootPath() {
        return RESTCONF_ROOT;
    }

    @Override
    public void subscribeEventStream(String streamId, String clientIpAddr,
                                     ChunkedOutput<String> output)
            throws RestconfException {
        //TODO: to be completed
    }

    @Override
    public CompletableFuture<RestconfRpcOutput> runRpc(URI uri,
                                                       ObjectNode input,
                                                       String clientIpAddress) {
        CompletableFuture<RestconfRpcOutput> result =
                CompletableFuture.supplyAsync(() -> executeRpc(uri, input, clientIpAddress));
        return result;
    }

    private RestconfRpcOutput executeRpc(URI uri, ObjectNode input, String clientIpAddress) {
        ResourceData rpcInputNode = convertJsonToDataNode(uri, input);
        ResourceId resourceId = rpcInputNode.resourceId();
        List<DataNode> inputDataNodeList = rpcInputNode.dataNodes();
        DataNode inputDataNode = inputDataNodeList.get(0);
        RpcInput rpcInput = new RpcInput(inputDataNode);

        RestconfRpcOutput restconfOutput = null;
        try {
            CompletableFuture<RpcOutput> rpcFuture =
                    dynamicConfigService.invokeRpc(resourceId, rpcInput);
            RpcOutput rpcOutput = rpcFuture.get();
            restconfOutput = RestconfUtils.convertRpcOutput(resourceId, rpcOutput);
        } catch (InterruptedException e) {
            log.error("ERROR: computeResultQ.take() has been interrupted.");
            log.debug("executeRpc Exception:", e);
            restconfOutput = new RestconfRpcOutput(INTERNAL_SERVER_ERROR, null);
            restconfOutput.reason("RPC execution has been interrupted");
        } catch (Exception e) {
            log.error("ERROR: executeRpc: {}", e.getMessage());
            log.debug("executeRpc Exception:", e);
            restconfOutput = new RestconfRpcOutput(INTERNAL_SERVER_ERROR, null);
            restconfOutput.reason(e.getMessage());
        }

        return restconfOutput;
    }

    private ResourceData getDataForStore(ResourceData resourceData) {
        List<DataNode> nodes = resourceData.dataNodes();
        ResourceId rid = resourceData.resourceId();
        DataNode.Builder dbr = null;
        ResourceId parentId = null;
        try {
            NodeKey lastKey = rid.nodeKeys().get(rid.nodeKeys().size() - 1);
            SchemaId sid = lastKey.schemaId();
            if (lastKey instanceof ListKey) {
                dbr = InnerNode.builder(
                        sid.name(), sid.namespace()).type(MULTI_INSTANCE_NODE);
                for (KeyLeaf keyLeaf : ((ListKey) lastKey).keyLeafs()) {
                    Object val = keyLeaf.leafValue();
                    dbr = dbr.addKeyLeaf(keyLeaf.leafSchema().name(),
                                         sid.namespace(), val);
                    dbr = dbr.createChildBuilder(keyLeaf.leafSchema().name(),
                                                 sid.namespace(), val)
                            .type(SINGLE_INSTANCE_LEAF_VALUE_NODE);
                    //Exit for key leaf node
                    dbr = dbr.exitNode();
                }
            } else {
                dbr = InnerNode.builder(
                        sid.name(), sid.namespace()).type(SINGLE_INSTANCE_NODE);
            }
            if (nodes != null && !nodes.isEmpty()) {
                // adding the parent node for given list of nodes
                for (DataNode node : nodes) {
                    dbr = ((InnerNode.Builder) dbr).addNode(node);
                }
            }
            parentId = rid.copyBuilder().removeLastKey().build();
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        ResourceData.Builder resData = DefaultResourceData.builder();
        resData.addDataNode(dbr.build());
        resData.resourceId(parentId);
        return resData.build();
    }
}
