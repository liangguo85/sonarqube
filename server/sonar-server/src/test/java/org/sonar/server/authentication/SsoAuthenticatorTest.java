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

package org.sonar.server.authentication;

import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.config.MapSettings;
import org.sonar.api.config.Settings;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.internal.AlwaysIncreasingSystem2;
import org.sonar.core.util.stream.Collectors;
import org.sonar.db.DbTester;
import org.sonar.db.user.GroupDto;
import org.sonar.db.user.UserDto;
import org.sonar.server.organization.DefaultOrganizationProvider;
import org.sonar.server.organization.TestDefaultOrganizationProvider;
import org.sonar.server.user.NewUserNotifier;
import org.sonar.server.user.UserUpdater;
import org.sonar.server.user.index.UserIndexer;

import static java.util.Arrays.stream;
import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.junit.rules.ExpectedException.none;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.sonar.db.user.UserTesting.newUserDto;

public class SsoAuthenticatorTest {

  @Rule
  public ExpectedException expectedException = none();

  @Rule
  public DbTester db = DbTester.create(new AlwaysIncreasingSystem2());

  private static final String DEFAULT_LOGIN = "john";
  private static final String DEFAULT_NAME = "John";
  private static final String DEFAULT_EMAIL = "john@doo.com";
  private static final String GROUP1 = "dev";
  private static final String GROUP2 = "admin";
  private static final String GROUPS = GROUP1 + "," + GROUP2;

  private static final Long NOW = 1_000_000L;
  private static final Long CLOSE_REFRESH_TIME = NOW - 1_000L;

  private static final UserDto DEFAULT_USER = newUserDto()
    .setLogin(DEFAULT_LOGIN)
    .setName(DEFAULT_NAME)
    .setEmail(DEFAULT_EMAIL)
    .setExternalIdentity(DEFAULT_LOGIN)
    .setExternalIdentityProvider("sonarqube");

  private GroupDto group1;
  private GroupDto group2;

  private System2 system2 = mock(System2.class);
  private Settings settings = new MapSettings();

  private DefaultOrganizationProvider defaultOrganizationProvider = TestDefaultOrganizationProvider.from(db);
  private UserIdentityAuthenticator userIdentityAuthenticator = new UserIdentityAuthenticator(
    db.getDbClient(),
    new UserUpdater(mock(NewUserNotifier.class), settings, db.getDbClient(), mock(UserIndexer.class), System2.INSTANCE, defaultOrganizationProvider),
    defaultOrganizationProvider);

  private HttpServletResponse response = mock(HttpServletResponse.class);
  private JwtHttpHandler jwtHttpHandler = mock(JwtHttpHandler.class);

  private SsoAuthenticator underTest = new SsoAuthenticator(system2, settings, userIdentityAuthenticator, jwtHttpHandler);

  @Before
  public void setUp() throws Exception {
    when(system2.now()).thenReturn(NOW);
    group1 = db.users().insertGroup(db.getDefaultOrganization(), GROUP1);
    group2 = db.users().insertGroup(db.getDefaultOrganization(), GROUP2);
    db.commit();
  }

  @Test
  public void create_user_when_authenticating_new_user() throws Exception {
    enableSso();
    setNotUserInToken();
    HttpServletRequest request = createRequest(DEFAULT_LOGIN, DEFAULT_NAME, DEFAULT_EMAIL, GROUPS);

    underTest.authenticate(request, response);

    verifyUserInDb(DEFAULT_LOGIN, DEFAULT_NAME, DEFAULT_EMAIL, group1, group2);
    verifyTokenIsUpdated(NOW);
  }

  @Test
  public void use_login_when_name_is_not_provided() throws Exception {
    enableSso();
    setNotUserInToken();

    underTest.authenticate(createRequest(DEFAULT_LOGIN, null, null, null), response);

    verifyUserInDb(DEFAULT_LOGIN, DEFAULT_LOGIN, null);
  }

  @Test
  public void update_user_when_authenticating_exiting_user() throws Exception {
    enableSso();
    setNotUserInToken();
    insertUser(newUserDto().setLogin(DEFAULT_LOGIN).setName("old name").setEmail("old email"), group1);
    // Name, email and groups are different
    HttpServletRequest request = createRequest(DEFAULT_LOGIN, DEFAULT_NAME, DEFAULT_EMAIL, GROUP2);

    underTest.authenticate(request, response);

    verifyUserInDb(DEFAULT_LOGIN, DEFAULT_NAME, DEFAULT_EMAIL, group2);
    verifyTokenIsUpdated(NOW);
  }

  @Test
  public void remove_groups_when_group_headers_is_empty() throws Exception {
    enableSso();
    setNotUserInToken();
    insertUser(DEFAULT_USER, group1);

    underTest.authenticate(createRequest(DEFAULT_LOGIN, DEFAULT_NAME, DEFAULT_EMAIL, ""), response);

    verityUserHasNoGroup(DEFAULT_LOGIN);
  }

  @Test
  public void remove_groups_when_group_headers_is_null() throws Exception {
    enableSso();
    setNotUserInToken();
    insertUser(DEFAULT_USER, group1);
    Map<String, String> headerValuesByName = new HashMap<>();
    headerValuesByName.put("X-Forwarded-Login", DEFAULT_LOGIN);
    headerValuesByName.put("X-Forwarded-Groups", null);

    underTest.authenticate(createRequest(headerValuesByName), response);

    verityUserHasNoGroup(DEFAULT_LOGIN);
  }

  @Test
  public void does_not_update_groups_when_no_group_headers() throws Exception {
    enableSso();
    setNotUserInToken();
    insertUser(DEFAULT_USER, group1);

    underTest.authenticate(createRequest(DEFAULT_LOGIN, DEFAULT_NAME, DEFAULT_EMAIL, null), response);

    verityUserGroups(DEFAULT_LOGIN, group1);
  }

  @Test
  public void does_not_update_user_when_user_is_in_token_and_refresh_time_is_close() throws Exception {
    enableSso();
    UserDto user = insertUser(DEFAULT_USER, group1);
    setUserInToken(user, CLOSE_REFRESH_TIME);
    HttpServletRequest request = createRequest(DEFAULT_LOGIN, "new name", "new email", GROUP2);

    underTest.authenticate(request, response);

    // User is not updated
    verifyUserInDb(DEFAULT_LOGIN, DEFAULT_NAME, DEFAULT_EMAIL, group1);
    verifyTokenIsNotUpdated();
  }

  @Test
  public void update_user_when_user_in_token_but_refresh_time_is_old() throws Exception {
    enableSso();
    UserDto user = insertUser(DEFAULT_USER, group1);
    // Refresh time was updated 6 minutes ago => more than 5 minutes
    setUserInToken(user, NOW - 6 * 60 * 1000L);
    HttpServletRequest request = createRequest(DEFAULT_LOGIN, "new name", "new email", GROUP2);

    underTest.authenticate(request, response);

    // User is updated
    verifyUserInDb(DEFAULT_LOGIN, "new name", "new email", group2);
    verifyTokenIsUpdated(NOW);
  }

  @Test
  public void update_user_when_user_in_token_but_no_refresh_time() throws Exception {
    enableSso();
    UserDto user = insertUser(DEFAULT_USER, group1);
    setUserInToken(user, null);
    HttpServletRequest request = createRequest(DEFAULT_LOGIN, "new name", "new email", GROUP2);

    underTest.authenticate(request, response);

    // User is updated
    verifyUserInDb(DEFAULT_LOGIN, "new name", "new email", group2);
    verifyTokenIsUpdated(NOW);
  }

  @Test
  public void use_refresh_time_from_settings() throws Exception {
    enableSso();
    settings.setProperty("sonar.sso.refreshIntervalInMinutes", "10");
    UserDto user = insertUser(DEFAULT_USER, group1);
    // Refresh time was updated 6 minutes ago => less than 10 minutes ago so not updated
    setUserInToken(user, NOW - 6 * 60 * 1000L);
    HttpServletRequest request = createRequest(DEFAULT_LOGIN, "new name", "new email", GROUP2);

    underTest.authenticate(request, response);

    // User is not updated
    verifyUserInDb(DEFAULT_LOGIN, DEFAULT_NAME, DEFAULT_EMAIL, group1);
    verifyTokenIsNotUpdated();
  }

  @Test
  public void update_user_when_login_from_token_is_different_than_login_from_request() throws Exception {
    enableSso();
    insertUser(DEFAULT_USER, group1);
    setUserInToken(DEFAULT_USER, CLOSE_REFRESH_TIME);
    HttpServletRequest request = createRequest("AnotherLogin", "Another name", "Another email", GROUP2);

    underTest.authenticate(request, response);

    verifyUserInDb("AnotherLogin", "Another name", "Another email", group2);
    verifyTokenIsUpdated(NOW);
  }

  @Test
  public void use_headers_from_settings() throws Exception {
    enableSso();
    setNotUserInToken();
    settings.setProperty("sonar.sso.loginHeader", "head-login");
    settings.setProperty("sonar.sso.nameHeader", "head-name");
    settings.setProperty("sonar.sso.emailHeader", "head-email");
    settings.setProperty("sonar.sso.groupsHeader", "head-groups");
    HttpServletRequest request = createRequest(ImmutableMap.of("head-login", DEFAULT_LOGIN, "head-name", DEFAULT_NAME, "head-email", DEFAULT_EMAIL, "head-groups", GROUPS));

    underTest.authenticate(request, response);

    verifyUserInDb(DEFAULT_LOGIN, DEFAULT_NAME, DEFAULT_EMAIL, group1, group2);
  }

  @Test
  public void detect_group_header_even_with_wrong_case() throws Exception {
    enableSso();
    setNotUserInToken();
    settings.setProperty("sonar.sso.loginHeader", "login");
    settings.setProperty("sonar.sso.nameHeader", "name");
    settings.setProperty("sonar.sso.emailHeader", "email");
    settings.setProperty("sonar.sso.groupsHeader", "Groups");
    HttpServletRequest request = createRequest(ImmutableMap.of("login", DEFAULT_LOGIN, "name", DEFAULT_NAME, "email", DEFAULT_EMAIL, "groups", GROUPS));

    underTest.authenticate(request, response);

    verifyUserInDb(DEFAULT_LOGIN, DEFAULT_NAME, DEFAULT_EMAIL, group1, group2);
  }

  @Test
  public void trim_groups() throws Exception {
    enableSso();
    setNotUserInToken();
    HttpServletRequest request = createRequest(DEFAULT_LOGIN, null, null, "  dev ,    admin ");

    underTest.authenticate(request, response);

    verifyUserInDb(DEFAULT_LOGIN, DEFAULT_LOGIN, null, group1, group2);
  }

  @Test
  public void does_not_authenticate_when_no_header() throws Exception {
    enableSso();
    setNotUserInToken();

    underTest.authenticate(createRequest(Collections.emptyMap()), response);

    verifyUserNotAuthenticated();
    verifyTokenIsNotUpdated();
  }

  @Test
  public void does_not_authenticate_when_not_enabled() throws Exception {
    settings.setProperty("sonar.sso.enable", false);

    underTest.authenticate(createRequest(DEFAULT_LOGIN, DEFAULT_NAME, DEFAULT_EMAIL, GROUPS), response);

    verifyUserNotAuthenticated();
    verifyZeroInteractions(jwtHttpHandler);
  }

  private void enableSso() {
    settings.setProperty("sonar.sso.enable", true);
  }

  private void setUserInToken(UserDto user, @Nullable Long lastRefreshTime) {
    when(jwtHttpHandler.getToken(any(HttpServletRequest.class), any(HttpServletResponse.class)))
      .thenReturn(Optional.of(new JwtHttpHandler.Token(
        user,
        lastRefreshTime == null ? Collections.emptyMap() : ImmutableMap.of("ssoLastRefreshTime", lastRefreshTime))));
  }

  private void setNotUserInToken() {
    when(jwtHttpHandler.getToken(any(HttpServletRequest.class), any(HttpServletResponse.class))).thenReturn(Optional.empty());
  }

  private UserDto insertUser(UserDto user, GroupDto... groups) {
    db.users().insertUser(user);
    stream(groups).forEach(group -> db.users().insertMember(group, user));
    db.commit();
    return user;
  }

  private static HttpServletRequest createRequest(Map<String, String> headerValuesByName) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    setHeaders(request, headerValuesByName);
    return request;
  }

  private static HttpServletRequest createRequest(String login, @Nullable String name, @Nullable String email, @Nullable String groups) {
    Map<String, String> headerValuesByName = new HashMap<>();
    headerValuesByName.put("X-Forwarded-Login", login);
    if (name != null) {
      headerValuesByName.put("X-Forwarded-Name", name);
    }
    if (email != null) {
      headerValuesByName.put("X-Forwarded-Email", email);
    }
    if (groups != null) {
      headerValuesByName.put("X-Forwarded-Groups", groups);
    }
    HttpServletRequest request = mock(HttpServletRequest.class);
    setHeaders(request, headerValuesByName);
    return request;
  }

  private static void setHeaders(HttpServletRequest request, Map<String, String> valuesByName) {
    valuesByName.entrySet().forEach(entry -> when(request.getHeader(entry.getKey())).thenReturn(entry.getValue()));
    when(request.getHeaderNames()).thenReturn(Collections.enumeration(valuesByName.keySet()));
  }

  private void verifyUserInDb(String expectedLogin, String expectedName, @Nullable String expectedEmail, GroupDto... expectedGroups) {
    UserDto userDto = db.users().selectUserByLogin(expectedLogin).get();
    assertThat(userDto.isActive()).isTrue();
    assertThat(userDto.getName()).isEqualTo(expectedName);
    assertThat(userDto.getEmail()).isEqualTo(expectedEmail);
    assertThat(userDto.getExternalIdentity()).isEqualTo(expectedLogin);
    assertThat(userDto.getExternalIdentityProvider()).isEqualTo("sonarqube");
    verityUserGroups(expectedLogin, expectedGroups);
  }

  private void verityUserGroups(String login, GroupDto... expectedGroups) {
    UserDto userDto = db.users().selectUserByLogin(login).get();
    if (expectedGroups.length == 0) {
      assertThat(db.users().selectGroupIdsOfUser(userDto)).isEmpty();
    } else {
      assertThat(db.users().selectGroupIdsOfUser(userDto)).containsOnly(stream(expectedGroups).map(GroupDto::getId).collect(Collectors.toList()).toArray(new Long[] {}));
    }
  }

  private void verityUserHasNoGroup(String login) {
    verityUserGroups(login);
  }

  private void verifyUserNotAuthenticated() {
    assertThat(db.countRowsOfTable(db.getSession(), "users")).isZero();
    verifyTokenIsNotUpdated();
  }

  private void verifyTokenIsUpdated(long refreshTime) {
    verify(jwtHttpHandler).generateToken(any(UserDto.class), eq(ImmutableMap.of("ssoLastRefreshTime", refreshTime)), any(HttpServletRequest.class), any(HttpServletResponse.class));
  }

  private void verifyTokenIsNotUpdated() {
    verify(jwtHttpHandler, never()).generateToken(any(UserDto.class), anyMap(), any(HttpServletRequest.class), any(HttpServletResponse.class));
  }

}
