package com.msm.core.dynamicquery.relation;

import com.msm.core.exceptions.CommonErrors;
import com.msm.core.metadata.ObjectMetadata;
import com.msm.core.security.ObjectAccessScopeResolver;
import com.msm.core.security.RequestContextHolder;
import com.msm.core.security.SecurityConditionProvider;
import com.msm.core.security.context.AuthorizationContext;
import com.msm.core.security.context.RequestContext;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;

/**
 * The caller's view of a related object, as if it read that object directly: the VIEW permission on it (on the
 * object whose permission it uses, for a child object) and its VIEW data scope on the related rows. A caller without
 * the permission gets {@link com.msm.core.exceptions.RelationAccessDeniedException}; without a data scope, no row.
 */
@RequiredArgsConstructor
public class SecuredRelationScope implements RelationScope {
    private final SecurityConditionProvider securityConditionProvider;

    @Override
    public void checkAccess(ObjectMetadata target) {
        RequestContext context = RequestContextHolder.getRequestContext();
        if (context.isSupperAdmin() || !target.isSecurityEnabled()) {
            return;
        }
        String permissionObject = ObjectAccessScopeResolver.resolveObjectAccessScope(target);
        AuthorizationContext authorization = context.getAuthorization();
        if (authorization == null || !authorization.canView(permissionObject)) {
            throw CommonErrors.relationAccessDenied(target.getName(), "No permission to view " + target.getName());
        }
    }

    @Override
    public Condition rowCondition(ObjectMetadata qualifiedTarget) {
        return securityConditionProvider.buildViewDataScopeCondition(qualifiedTarget);
    }
}
