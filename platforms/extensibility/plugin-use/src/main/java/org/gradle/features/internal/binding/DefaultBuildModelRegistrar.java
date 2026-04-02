/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.features.internal.binding;

import org.gradle.api.model.ObjectFactory;
import org.gradle.features.binding.BuildModel;
import org.gradle.features.binding.Definition;
import org.gradle.internal.Cast;
import org.gradle.internal.inspection.DefaultTypeParameterInspection;
import org.gradle.internal.inspection.TypeParameterInspection;

import javax.inject.Inject;
import java.util.Map;

public abstract class DefaultBuildModelRegistrar implements BuildModelRegistrarInternal {

    @Inject
    protected abstract ProjectFeatureApplicator getProjectFeatureApplicator();

    @Inject
    protected abstract ProjectFeatureDeclarations getProjectFeatureRegistry();

    private final ObjectFactory objectFactory;

    @Inject
    public DefaultBuildModelRegistrar(ObjectFactory objectFactory) {
        this.objectFactory = objectFactory;
    }

    @Override
    public <T extends Definition<V>, V extends BuildModel> V registerBuildModel(T definition, Class<? extends V> implementationType) {
        ProjectFeatureSupportInternal.ProjectFeatureDefinitionContext maybeContext = ProjectFeatureSupportInternal.tryGetContext(definition);
        if (maybeContext != null) {
            return Cast.uncheckedCast(maybeContext.getBuildModel());
        }

        V buildModel = ProjectFeatureSupportInternal.createBuildModelInstance(objectFactory, implementationType);
        ProjectFeatureSupportInternal.attachDefinitionContext(definition, buildModel, getProjectFeatureApplicator(), getProjectFeatureRegistry(), objectFactory);

        return buildModel;
    }

    @Override
    public <T extends Definition<V>, V extends BuildModel> V registerBuildModel(T definition) {
        @SuppressWarnings("rawtypes")
        TypeParameterInspection<Definition, BuildModel> inspection = new DefaultTypeParameterInspection<>(Definition.class, BuildModel.class, BuildModel.None.class);
        Class<V> modelType = inspection.parameterTypeFor(definition.getClass());

        return registerBuildModel(definition, modelType);
    }

    @Override
    public <T extends Definition<V>, V extends BuildModel> V registerBuildModel(T definition, Map<Class<?>, Class<?>> nestedBuildModelTypesToImplementationTypes) {
        @SuppressWarnings("rawtypes")
        TypeParameterInspection<Definition, BuildModel> inspection = new DefaultTypeParameterInspection<>(Definition.class, BuildModel.class, BuildModel.None.class);
        Class<V> modelType = inspection.parameterTypeFor(definition.getClass());

        if (nestedBuildModelTypesToImplementationTypes.containsKey(modelType)) {
            Class<? extends V> buildModelImplementationType = Cast.uncheckedCast(nestedBuildModelTypesToImplementationTypes.get(modelType));
            return registerBuildModel(definition, buildModelImplementationType);
        } else {
            return registerBuildModel(definition, modelType);
        }
    }
}
