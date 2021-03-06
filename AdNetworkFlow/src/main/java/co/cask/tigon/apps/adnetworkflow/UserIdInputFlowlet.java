/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.tigon.apps.adnetworkflow;

import co.cask.http.AbstractHttpHandler;
import co.cask.http.HttpHandler;
import co.cask.http.HttpResponder;
import co.cask.http.NettyHttpService;
import co.cask.tigon.api.annotation.Tick;
import co.cask.tigon.api.flow.flowlet.AbstractFlowlet;
import co.cask.tigon.api.flow.flowlet.Flowlet;
import co.cask.tigon.api.flow.flowlet.FlowletContext;
import co.cask.tigon.api.flow.flowlet.FlowletSpecification;
import co.cask.tigon.api.flow.flowlet.OutputEmitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Queues;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * A {@link Flowlet} that starts a {@link NettyHttpService} that accepts requests to register new user-ids.
 *
 * <p>
 * In a production application, this is often implemented as a stream of cookie information associated with incoming
 * ad requests.
 * </p>
 *
 */
public final class UserIdInputFlowlet extends AbstractFlowlet {

  private static final Logger LOG = LoggerFactory.getLogger(UserIdInputFlowlet.class);
  private OutputEmitter<String> idEmitter;
  private NettyHttpService service;
  private InetSocketAddress address;

  @Override
  public FlowletSpecification configure() {
    return FlowletSpecification.Builder.with()
      .setName(getName())
      .setDescription(getDescription())
      .setMaxInstances(1)
      .build();
  }

  @Override
  public void initialize(FlowletContext context) throws Exception {
    super.initialize(context);
    int port = 0;
    if (context.getRuntimeArguments().containsKey("input.service.port")) {
      port = Integer.parseInt(context.getRuntimeArguments().get("input.service.port"));
    }
    // Create and start the Netty service.
    service = NettyHttpService.builder()
      .addHttpHandlers(ImmutableList.of(new IdHandler()))
      .setHost("0.0.0.0")
      .setPort(port)
      .build();
    service.startAndWait();

    address = service.getBindAddress();
    LOG.info("User ID input server running at: " + address.toString());
  }

  @Tick(delay = 1L, unit = TimeUnit.SECONDS)
  @SuppressWarnings("UnusedDeclaration")
  public void process() throws Exception {
    HttpURLConnection urlConn = (HttpURLConnection) new URL("http", address.getHostName(),
                                                            address.getPort(), "/id").openConnection();
    urlConn.setReadTimeout(2000);
    if (urlConn.getResponseCode() == 200) {
      List<String> userIds = new Gson().fromJson(new InputStreamReader(urlConn.getInputStream()),
                                                 new TypeToken<List<String>>() { }.getType());
      for (String id : userIds) {
        idEmitter.emit(id);
        LOG.info("Initiating ad auction for user id : {}", id);
      }
    }
  }

  @Override
  public void destroy() {
    service.stopAndWait();
  }

  /**
   * A {@link HttpHandler} that exposes endpoints for submitting a new user-id and for polling the queue of
   * users yet to be auctioned.
   */
  public static final class IdHandler extends AbstractHttpHandler {
    private static final Queue<String> IDS = Queues.newConcurrentLinkedQueue();

    @Path("/id/{id}")
    @POST
    public void newCustomer(HttpRequest request, HttpResponder responder, @PathParam("id") String id) {
      IDS.add(id);
      responder.sendStatus(HttpResponseStatus.OK);
    }

    @Path("id")
    @GET
    public void pollCustomers(HttpRequest request, HttpResponder responder) {
      String[] queuedUsers = IDS.toArray(new String[IDS.size()]);
      IDS.clear();
      if (queuedUsers.length != 0) {
        responder.sendJson(HttpResponseStatus.OK, queuedUsers);
      } else {
        responder.sendStatus(HttpResponseStatus.NO_CONTENT);
      }
    }
  }
}
