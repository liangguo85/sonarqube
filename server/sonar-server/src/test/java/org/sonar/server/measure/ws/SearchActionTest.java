/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.measure.ws;

import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.System2;
import org.sonar.api.web.UserRole;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.user.UserDto;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.TestRequest;
import org.sonar.server.ws.WsActionTester;
import org.sonarqube.ws.WsMeasures;
import org.sonarqube.ws.WsMeasures.Measure;
import org.sonarqube.ws.WsMeasures.SearchWsResponse;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.sonar.api.utils.DateUtils.parseDateTime;
import static org.sonar.db.component.ComponentTesting.newDirectory;
import static org.sonar.db.component.ComponentTesting.newFileDto;
import static org.sonar.db.component.ComponentTesting.newProjectDto;
import static org.sonar.db.component.SnapshotTesting.newAnalysis;
import static org.sonar.db.measure.MeasureTesting.newMeasureDto;
import static org.sonar.db.metric.MetricTesting.newMetricDto;
import static org.sonar.test.JsonAssert.assertJson;
import static org.sonarqube.ws.MediaTypes.PROTOBUF;
import static org.sonarqube.ws.client.measure.MeasuresWsParameters.PARAM_COMPONENT_KEYS;
import static org.sonarqube.ws.client.measure.MeasuresWsParameters.PARAM_METRIC_KEYS;

public class SearchActionTest {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();

  @Rule
  public DbTester db = DbTester.create(System2.INSTANCE);

  ComponentDbTester componentDb = new ComponentDbTester(db);
  DbClient dbClient = db.getDbClient();
  DbSession dbSession = db.getSession();

  UserDto user;

  WsActionTester ws = new WsActionTester(new SearchAction(userSession, dbClient));

  @Before
  public void setUp() throws Exception {
    user = db.users().insertUser("john");
    userSession.login(user);
  }

  @Test
  public void json_example() {
    List<String> componentKeys = insertJsonExampleData();

    String result = ws.newRequest()
      .setParam(PARAM_COMPONENT_KEYS, Joiner.on(",").join(componentKeys))
      .setParam(PARAM_METRIC_KEYS, "ncloc, complexity, new_violations")
      .execute()
      .getInput();

    assertJson(result).withStrictArrayOrder().isSimilarTo(ws.getDef().responseExampleAsString());
  }

  @Test
  public void return_measures() throws Exception {
    ComponentDto project = newProjectDto();
    SnapshotDto projectSnapshot = componentDb.insertProjectAndSnapshot(project);
    setBrowsePermissionOnUser(project);
    MetricDto coverage = insertCoverageMetric();
    dbClient.measureDao().insert(dbSession, newMeasureDto(coverage, project, projectSnapshot).setValue(15.5d));
    db.commit();

    SearchWsResponse result = call(singletonList(project.key()), singletonList("coverage"));

    List<Measure> measures = result.getMeasuresList();
    assertThat(measures).hasSize(1);
    Measure measure = measures.get(0);
    assertThat(measure.getMetric()).isEqualTo("coverage");
    assertThat(measure.getValue()).isEqualTo("15.5");
  }

  @Test
  public void return_measures_on_periods() throws Exception {
    ComponentDto project = newProjectDto();
    SnapshotDto projectSnapshot = componentDb.insertProjectAndSnapshot(project);
    setBrowsePermissionOnUser(project);
    MetricDto coverage = insertCoverageMetric();
    dbClient.measureDao().insert(dbSession,
      newMeasureDto(coverage, project, projectSnapshot)
        .setValue(15.5d)
        .setVariation(1, 10d)
        .setVariation(2, 15.5d)
        .setVariation(3, 20d));
    db.commit();

    SearchWsResponse result = call(singletonList(project.key()), singletonList("coverage"));

    List<Measure> measures = result.getMeasuresList();
    assertThat(measures).hasSize(1);
    Measure measure = measures.get(0);
    assertThat(measure.getMetric()).isEqualTo("coverage");
    assertThat(measure.getValue()).isEqualTo("15.5");
    assertThat(measure.getPeriods().getPeriodsValueList()).extracting(WsMeasures.PeriodValue::getIndex, WsMeasures.PeriodValue::getValue)
      .containsOnly(tuple(1, "10.0"), tuple(2, "15.5"), tuple(3, "20.0"));
  }

  @Test
  public void add_best_values_when_no_value() {
    ComponentDto projectDto = newProjectDto("project-uuid");
    SnapshotDto projectSnapshot = componentDb.insertProjectAndSnapshot(projectDto);
    ComponentDto directoryDto = newDirectory(projectDto, "directory-uuid", "path/to/directory").setName("directory-1");
    componentDb.insertComponent(directoryDto);
    ComponentDto file = newFileDto(directoryDto, null, "file-uuid").setName("file-1");
    componentDb.insertComponent(file);
    MetricDto coverage = insertCoverageMetric();
    dbClient.metricDao().insert(dbSession, newMetricDto()
      .setKey("ncloc")
      .setValueType(Metric.ValueType.INT.name())
      .setOptimizedBestValue(true)
      .setBestValue(100d)
      .setWorstValue(1000d));
    dbClient.metricDao().insert(dbSession, newMetricDtoWithoutOptimization()
      .setKey("new_violations")
      .setOptimizedBestValue(true)
      .setBestValue(1984.0d)
      .setValueType(Metric.ValueType.INT.name()));
    dbClient.measureDao().insert(dbSession,
      newMeasureDto(coverage, file, projectSnapshot).setValue(15.5d),
      newMeasureDto(coverage, directoryDto, projectSnapshot).setValue(42.0d));
    db.commit();
    setBrowsePermissionOnUser(projectDto);

    SearchWsResponse result = call(newArrayList(directoryDto.key(), file.key()), newArrayList("ncloc", "coverage", "new_violations"));

    // directory is not eligible for best value
    assertThat(result.getMeasuresList().stream()
      .filter(measure -> directoryDto.key().equals(measure.getComponent()))
      .map(Measure::getMetric))
        .containsOnly("coverage");
    // file measures
    List<Measure> fileMeasures = result.getMeasuresList().stream().filter(measure -> file.key().equals(measure.getComponent())).collect(Collectors.toList());
    assertThat(fileMeasures).extracting(Measure::getMetric).containsOnly("ncloc", "coverage", "new_violations");
    assertThat(fileMeasures).extracting(Measure::getValue).containsOnly("100", "15.5", "");
  }

  @Test
  public void sort_by_metric_key_then_component_name() throws Exception {
    MetricDto coverage = insertCoverageMetric();
    MetricDto complexity = insertComplexityMetric();
    ComponentDto project = newProjectDto();
    SnapshotDto projectSnapshot = componentDb.insertProjectAndSnapshot(project);
    setBrowsePermissionOnUser(project);
    ComponentDto file1 = componentDb.insertComponent(newFileDto(project).setName("C"));
    ComponentDto file2 = componentDb.insertComponent(newFileDto(project).setName("A"));
    ComponentDto file3 = componentDb.insertComponent(newFileDto(project).setName("B"));
    dbClient.measureDao().insert(dbSession, newMeasureDto(coverage, file1, projectSnapshot).setValue(5.5d));
    dbClient.measureDao().insert(dbSession, newMeasureDto(coverage, file2, projectSnapshot).setValue(6.5d));
    dbClient.measureDao().insert(dbSession, newMeasureDto(coverage, file3, projectSnapshot).setValue(7.5d));
    dbClient.measureDao().insert(dbSession, newMeasureDto(complexity, file1, projectSnapshot).setValue(10d));
    dbClient.measureDao().insert(dbSession, newMeasureDto(complexity, file2, projectSnapshot).setValue(15d));
    dbClient.measureDao().insert(dbSession, newMeasureDto(complexity, file3, projectSnapshot).setValue(20d));
    db.commit();

    SearchWsResponse result = call(asList(file1.key(), file2.key(), file3.key()), asList("coverage", "complexity"));

    assertThat(result.getMeasuresList()).extracting(Measure::getMetric, Measure::getComponent)
      .containsExactly(
        tuple("complexity", file2.key()), tuple("complexity", file3.key()), tuple("complexity", file1.key()),
        tuple("coverage", file2.key()), tuple("coverage", file3.key()), tuple("coverage", file1.key()));
  }

  @Test
  public void only_returns_authorized_components() {
    MetricDto metricDto = insertComplexityMetric();
    ComponentDto project1 = newProjectDto();
    SnapshotDto projectSnapshot1 = componentDb.insertProjectAndSnapshot(project1);
    ComponentDto file1 = componentDb.insertComponent(newFileDto(project1));
    ComponentDto project2 = newProjectDto();
    SnapshotDto projectSnapshot2 = componentDb.insertProjectAndSnapshot(project2);
    ComponentDto file2 = componentDb.insertComponent(newFileDto(project2));
    dbClient.measureDao().insert(dbSession,
      newMeasureDto(metricDto, file1, projectSnapshot1).setValue(15.5d),
      newMeasureDto(metricDto, file2, projectSnapshot2).setValue(42.0d));
    db.commit();
    setBrowsePermissionOnUser(project1);

    SearchWsResponse result = call(asList(file1.key(), file2.key()), singletonList("complexity"));

    assertThat(result.getMeasuresList()).extracting(Measure::getComponent).containsOnly(file1.key());
  }

  @Test
  public void fail_if_no_metric() {
    ComponentDto project = componentDb.insertProject();
    setBrowsePermissionOnUser(project);

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("The 'metricKeys' parameter is missing");

    call(singletonList(project.uuid()), null);
  }

  @Test
  public void fail_if_empty_metric() {
    ComponentDto project = componentDb.insertProject();
    setBrowsePermissionOnUser(project);

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Metric keys must be provided");

    call(singletonList(project.uuid()), emptyList());
  }

  @Test
  public void fail_if_unknown_metric() {
    ComponentDto project = componentDb.insertProject();
    setBrowsePermissionOnUser(project);
    insertComplexityMetric();

    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("The following metrics are not found: ncloc, violations");

    call(singletonList(project.key()), newArrayList("violations", "complexity", "ncloc"));
  }

  @Test
  public void fail_if_no_component() {
    insertComplexityMetric();

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Component keys must be provided");

    call(null, singletonList("complexity"));
  }

  @Test
  public void fail_if_empty_component_key() {
    insertComplexityMetric();

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Component keys must be provided");

    call(emptyList(), singletonList("complexity"));
  }

  @Test
  public void fail_if_more_than_100_component_key() {
    List<String> keys = IntStream.rangeClosed(1, 101)
      .mapToObj(i -> componentDb.insertProject())
      .map(ComponentDto::key)
      .collect(Collectors.toList());
    insertComplexityMetric();

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("101 components provided, more than maximum authorized (100)");

    call(keys, singletonList("complexity"));
  }

  @Test
  public void definition() {
    WebService.Action result = ws.getDef();

    assertThat(result.key()).isEqualTo("search");
    assertThat(result.isPost()).isFalse();
    assertThat(result.isInternal()).isTrue();
    assertThat(result.since()).isEqualTo("6.2");
    assertThat(result.params()).hasSize(2);
    assertThat(result.responseExampleAsString()).isNotEmpty();
    assertThat(result.description()).isEqualToIgnoringWhitespace("" +
      "Search for component measures ordered by component names.<br>" +
      "At most 100 components can be provided.<br>" +
      "Requires one of the following permissions:" +
      "<ul>" +
      " <li>'Administer System'</li>" +
      " <li>'Administer' rights on the provided components</li>" +
      " <li>'Browse' on the provided components</li>" +
      "</ul>");
  }

  private SearchWsResponse call(@Nullable List<String> keys, @Nullable List<String> metrics) {
    TestRequest request = ws.newRequest()
      .setMediaType(PROTOBUF);

    if (keys != null) {
      request.setParam(PARAM_COMPONENT_KEYS, String.join(",", keys));
    }
    if (metrics != null) {
      request.setParam(PARAM_METRIC_KEYS, String.join(",", metrics));
    }

    try {
      return SearchWsResponse.parseFrom(request.execute().getInputStream());
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private static MetricDto newMetricDtoWithoutOptimization() {
    return newMetricDto()
      .setWorstValue(null)
      .setBestValue(null)
      .setOptimizedBestValue(false)
      .setUserManaged(false);
  }

  private MetricDto insertNewViolationsMetric() {
    MetricDto metric = dbClient.metricDao().insert(dbSession, newMetricDtoWithoutOptimization()
      .setKey("new_violations")
      .setShortName("New issues")
      .setDescription("New Issues")
      .setDomain("Issues")
      .setValueType("INT")
      .setDirection(-1)
      .setQualitative(true)
      .setHidden(false)
      .setUserManaged(false)
      .setOptimizedBestValue(true)
      .setBestValue(0.0d));
    db.commit();
    return metric;
  }

  private MetricDto insertNclocMetric() {
    MetricDto metric = dbClient.metricDao().insert(dbSession, newMetricDtoWithoutOptimization()
      .setKey("ncloc")
      .setShortName("Lines of code")
      .setDescription("Non Commenting Lines of Code")
      .setDomain("Size")
      .setValueType("INT")
      .setDirection(-1)
      .setQualitative(false)
      .setHidden(false)
      .setUserManaged(false));
    db.commit();
    return metric;
  }

  private MetricDto insertComplexityMetric() {
    MetricDto metric = dbClient.metricDao().insert(dbSession, newMetricDtoWithoutOptimization()
      .setKey("complexity")
      .setShortName("Complexity")
      .setDescription("Cyclomatic complexity")
      .setDomain("Complexity")
      .setValueType("INT")
      .setDirection(-1)
      .setQualitative(false)
      .setHidden(false)
      .setUserManaged(false));
    db.commit();
    return metric;
  }

  private MetricDto insertCoverageMetric() {
    MetricDto metric = dbClient.metricDao().insert(dbSession, newMetricDtoWithoutOptimization()
      .setKey("coverage")
      .setShortName("Coverage")
      .setDescription("Code Coverage")
      .setDomain("Coverage")
      .setValueType(Metric.ValueType.FLOAT.name())
      .setDirection(1)
      .setQualitative(false)
      .setHidden(false)
      .setUserManaged(false));
    db.commit();
    return metric;
  }

  private List<String> insertJsonExampleData() {
    List<String> componentKeys = new ArrayList<>();
    ComponentDto project = newProjectDto("project-id")
      .setKey("MY_PROJECT")
      .setName("My Project")
      .setDescription("My Project Description")
      .setQualifier(Qualifiers.PROJECT);
    componentKeys.add(project.key());
    componentDb.insertComponent(project);
    SnapshotDto projectSnapshot = dbClient.snapshotDao().insert(dbSession, newAnalysis(project)
      .setPeriodDate(1, parseDateTime("2016-01-11T10:49:50+0100").getTime())
      .setPeriodMode(1, "previous_version")
      .setPeriodParam(1, "1.0-SNAPSHOT")
      .setPeriodDate(2, parseDateTime("2016-01-11T10:50:06+0100").getTime())
      .setPeriodMode(2, "previous_analysis")
      .setPeriodParam(2, "2016-01-11")
      .setPeriodDate(3, parseDateTime("2016-01-11T10:38:45+0100").getTime())
      .setPeriodMode(3, "days")
      .setPeriodParam(3, "30"));

    ComponentDto file1 = componentDb.insertComponent(newFileDto(project, null)
      .setUuid("AVIwDXE-bJbJqrw6wFv5")
      .setKey("com.sonarsource:java-markdown:src/main/java/com/sonarsource/markdown/impl/ElementImpl.java")
      .setName("ElementImpl.java")
      .setLanguage("java")
      .setQualifier(Qualifiers.FILE)
      .setPath("src/main/java/com/sonarsource/markdown/impl/ElementImpl.java"));
    componentKeys.add(file1.key());
    ComponentDto file = newFileDto(project, null)
      .setUuid("AVIwDXE_bJbJqrw6wFwJ")
      .setKey("com.sonarsource:java-markdown:src/test/java/com/sonarsource/markdown/impl/ElementImplTest.java")
      .setName("ElementImplTest.java")
      .setLanguage("java")
      .setQualifier(Qualifiers.UNIT_TEST_FILE)
      .setPath("src/test/java/com/sonarsource/markdown/impl/ElementImplTest.java");
    componentKeys.add(file.key());
    componentDb.insertComponent(file);
    ComponentDto dir = componentDb.insertComponent(newDirectory(project, "src/main/java/com/sonarsource/markdown/impl")
      .setUuid("AVIwDXE-bJbJqrw6wFv8")
      .setKey("com.sonarsource:java-markdown:src/main/java/com/sonarsource/markdown/impl")
      .setQualifier(Qualifiers.DIRECTORY));
    componentKeys.add(dir.key());

    MetricDto complexity = insertComplexityMetric();
    dbClient.measureDao().insert(dbSession,
      newMeasureDto(complexity, file1, projectSnapshot)
        .setValue(12.0d),
      newMeasureDto(complexity, dir, projectSnapshot)
        .setValue(35.0d)
        .setVariation(2, 0.0d),
      newMeasureDto(complexity, project, projectSnapshot)
        .setValue(42.0d));

    MetricDto ncloc = insertNclocMetric();
    dbClient.measureDao().insert(dbSession,
      newMeasureDto(ncloc, file1, projectSnapshot)
        .setValue(114.0d),
      newMeasureDto(ncloc, dir, projectSnapshot)
        .setValue(217.0d)
        .setVariation(2, 0.0d),
      newMeasureDto(ncloc, project, projectSnapshot)
        .setValue(1984.0d));

    MetricDto newViolations = insertNewViolationsMetric();
    dbClient.measureDao().insert(dbSession,
      newMeasureDto(newViolations, file1, projectSnapshot)
        .setVariation(1, 25.0d)
        .setVariation(2, 0.0d)
        .setVariation(3, 25.0d),
      newMeasureDto(newViolations, dir, projectSnapshot)
        .setVariation(1, 25.0d)
        .setVariation(2, 0.0d)
        .setVariation(3, 25.0d),
      newMeasureDto(newViolations, project, projectSnapshot)
        .setVariation(1, 255.0d)
        .setVariation(2, 0.0d)
        .setVariation(3, 255.0d));
    db.commit();
    setBrowsePermissionOnUser(project);
    return componentKeys;
  }

  private void setBrowsePermissionOnUser(ComponentDto project) {
    db.users().insertProjectPermissionOnUser(user, UserRole.USER, project);
    dbSession.commit();
  }
}
