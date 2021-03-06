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
import { combineReducers } from 'redux';
import projects, * as fromProjects from './projects/reducer';
import state from './state/reducer';
import filters, * as fromFilters from './filters/reducer';

export default combineReducers({ projects, state, filters });

export const getProjects = state => (
    fromProjects.getProjects(state.projects)
);

export const getState = state => (
    state.state
);

export const getFilterStatus = (state, key) => (
    fromFilters.getFilterStatus(state.filters, key)
);
