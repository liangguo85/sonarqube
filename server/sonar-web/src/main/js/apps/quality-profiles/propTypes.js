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
import { PropTypes } from 'react';

const { shape, string, number, bool, arrayOf } = PropTypes;

export const ProfileType = shape({
  key: string.isRequired,
  name: string.isRequired,
  isDefault: bool.isRequired,
  isInherited: bool.isRequired,
  language: string.isRequired,
  languageName: string.isRequired,
  activeRuleCount: number.isRequired,
  activeDeprecatedRuleCount: number.isRequired,
  projectCount: number,
  parentKey: string,
  parentName: string,
  userUpdatedAt: string,
  lastUsed: string
});

export const ProfilesListType = arrayOf(ProfileType);

const LanguageType = shape({
  key: string.isRequired,
  name: string.isRequired
});

export const LanguagesListType = arrayOf(LanguageType);
