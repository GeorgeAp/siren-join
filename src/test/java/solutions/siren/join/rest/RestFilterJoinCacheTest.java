/**
 * Copyright (c) 2016, SIREn Solutions. All Rights Reserved.
 *
 * This file is part of the SIREn project.
 *
 * SIREn is a free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * SIREn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package solutions.siren.join.rest;

import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.node.Node;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.rest.client.http.HttpResponse;
import org.junit.Test;
import solutions.siren.join.SirenJoinTestCase;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.index.query.QueryBuilders.*;
import static solutions.siren.join.index.query.QueryBuilders.filterJoin;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

@ESIntegTestCase.ClusterScope(scope= ESIntegTestCase.Scope.SUITE, numDataNodes=2)
public class RestFilterJoinCacheTest extends SirenJoinTestCase {

  @Override
  protected Settings nodeSettings(int nodeOrdinal) {
    return Settings.builder()
            .put(Node.HTTP_ENABLED, true) // enable http for these tests
            .put(super.nodeSettings(nodeOrdinal)).build();
  }

  @Test
  public void testClearCache() throws IOException, ExecutionException, InterruptedException {
    // load some data and warm the cache
    this.warmCache();

    HttpResponse response = httpClient().method("GET").path("/_filter_join/cache/stats").execute();
    assertThat(response.getStatusCode(), equalTo(RestStatus.OK.getStatus()));
    Map<String, Object> map = XContentHelper.convertToMap(new BytesArray(response.getBody().getBytes("UTF-8")), false).v2();
    Map nodes = (Map) map.get("nodes");
    for (Object node : nodes.values()) {
      Map stats = (Map) ((Map) node).get("stats");
      assertThat((Integer) stats.get("size"), equalTo(1));
    }

    response = httpClient().method("GET").path("/_filter_join/cache/clear").execute();
    assertThat(response.getStatusCode(), equalTo(RestStatus.OK.getStatus()));

    response = httpClient().method("GET").path("/_filter_join/cache/stats").execute();
    assertThat(response.getStatusCode(), equalTo(RestStatus.OK.getStatus()));
    map = XContentHelper.convertToMap(new BytesArray(response.getBody().getBytes("UTF-8")), false).v2();
    nodes = (Map) map.get("nodes");
    for (Object node : nodes.values()) {
      Map stats = (Map) ((Map) node).get("stats");
      assertThat((Integer) stats.get("size"), equalTo(0));
    }
  }

  private void warmCache() throws ExecutionException, InterruptedException, IOException {
    this.loadData();
    for (int i = 0; i < 20; i++) {
      this.runQuery();
    }
  }

  private void loadData() throws ExecutionException, InterruptedException {
    assertAcked(prepareCreate("index1").addMapping("type", "id", "type=string", "foreign_key", "type=string"));
    assertAcked(prepareCreate("index2").addMapping("type", "id", "type=string", "tag", "type=string"));

    ensureGreen();

    indexRandom(true,
            client().prepareIndex("index1", "type", "1").setSource("id", "1", "foreign_key", new String[]{"1", "3"}),
            client().prepareIndex("index1", "type", "2").setSource("id", "2"),
            client().prepareIndex("index1", "type", "3").setSource("id", "3", "foreign_key", new String[]{"2"}),
            client().prepareIndex("index1", "type", "4").setSource("id", "4", "foreign_key", new String[]{"1", "4"}),

            client().prepareIndex("index2", "type", "1").setSource("id", "1", "tag", "aaa"),
            client().prepareIndex("index2", "type", "2").setSource("id", "2", "tag", "aaa"),
            client().prepareIndex("index2", "type", "3").setSource("id", "3", "tag", "bbb"),
            client().prepareIndex("index2", "type", "4").setSource("id", "4", "tag", "ccc") );
  }

  private void runQuery() throws IOException {
    // Check body search query with filter join
    String q = boolQuery().filter(
            filterJoin("foreign_key").indices("index2").types("type").path("id").query(
                    boolQuery().filter(termQuery("tag", "aaa"))
            )).toString();
    String body = "{ \"query\" : " + q + "}";

    HttpResponse response = httpClient().method("GET").path("/_coordinate_search").body(body).execute();
    assertThat(response.getStatusCode(), equalTo(RestStatus.OK.getStatus()));
    Map<String, Object> map = XContentHelper.convertToMap(new BytesArray(response.getBody().getBytes("UTF-8")), false).v2();
    assertThat((Integer) ((Map) map.get("hits")).get("total"), equalTo(3));
  }

}
