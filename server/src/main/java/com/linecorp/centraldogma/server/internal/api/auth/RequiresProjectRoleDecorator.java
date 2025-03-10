/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.api.auth;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.User;

/**
 * A decorator for checking the project role of the user sent a request.
 */
public final class RequiresProjectRoleDecorator extends SimpleDecoratingHttpService {

    private final MetadataService mds;
    private final ProjectRole requiredRole;

    RequiresProjectRoleDecorator(HttpService delegate, MetadataService mds, ProjectRole requiredRole) {
        super(delegate);
        this.mds = requireNonNull(mds, "mds");
        this.requiredRole = requireNonNull(requiredRole, "requiredRole");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final User user = AuthUtil.currentUser(ctx);

        final String projectName = ctx.pathParam("projectName");
        checkArgument(!isNullOrEmpty(projectName), "no project name is specified");
        if (user.isSystemAdmin()) {
            return unwrap().serve(ctx, req);
        }

        try {
            return HttpResponse.of(mds.findProjectRole(projectName, user).handle((role, cause) -> {
                if (cause != null) {
                    return handleException(ctx, cause);
                }
                if (role == null || !role.has(requiredRole)) {
                    return HttpApiUtil.throwResponse(
                            ctx, HttpStatus.FORBIDDEN,
                            "You must have the %s project role to access the project '%s'.",
                            requiredRole, projectName);
                }
                try {
                    return unwrap().serve(ctx, req);
                } catch (Exception e) {
                    return Exceptions.throwUnsafely(e);
                }
            }));
        } catch (Throwable cause) {
            return handleException(ctx, cause);
        }
    }

    static HttpResponse handleException(ServiceRequestContext ctx, Throwable cause) {
        cause = Exceptions.peel(cause);
        if (cause instanceof RepositoryNotFoundException ||
            cause instanceof ProjectNotFoundException) {
            return HttpApiUtil.newResponse(ctx, HttpStatus.NOT_FOUND, cause);
        } else {
            return Exceptions.throwUnsafely(cause);
        }
    }

    public static final class RequiresProjectRoleDecoratorFactory
            implements DecoratorFactoryFunction<RequiresProjectRole> {

        private final MetadataService mds;

        public RequiresProjectRoleDecoratorFactory(MetadataService mds) {
            this.mds = mds;
        }

        @Override
        public Function<? super HttpService, ? extends HttpService> newDecorator(
                RequiresProjectRole parameter) {
            return delegate -> new RequiresProjectRoleDecorator(
                    delegate, mds, parameter.value());
        }
    }
}
