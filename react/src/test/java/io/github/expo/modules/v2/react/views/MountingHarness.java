package io.github.expo.modules.v2.react.views;

import com.facebook.react.ReactRootView;
import com.facebook.react.bridge.queue.MessageQueueThreadSpec;
import com.facebook.react.bridge.queue.ReactQueueConfiguration;
import com.facebook.react.bridge.queue.ReactQueueConfigurationImpl;
import com.facebook.react.bridge.queue.ReactQueueConfigurationSpec;
import com.facebook.react.fabric.mounting.MountingManager;
import com.facebook.react.fabric.mounting.SurfaceMountingManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.ViewManager;
import com.facebook.react.uimanager.ViewManagerRegistry;
import java.util.Collections;

/**
 * Builds React Native's Java mounting layer for one view manager, as RN's own
 * {@code SurfaceMountingManagerTest} does. Java because {@code MountingManager} and
 * {@code ReactQueueConfigurationImpl} are Kotlin-{@code internal} to React Native.
 */
final class MountingHarness {
  private MountingHarness() {}

  static ReactQueueConfiguration mainThreadQueues() {
    ReactQueueConfigurationSpec spec =
        ReactQueueConfigurationSpec.Companion.builder()
            .setJSQueueThreadSpec(MessageQueueThreadSpec.mainThreadSpec())
            .setNativeModulesQueueThreadSpec(MessageQueueThreadSpec.mainThreadSpec())
            .build();
    return ReactQueueConfigurationImpl.create(
        spec,
        e -> {
          throw new RuntimeException(e);
        });
  }

  static SurfaceMountingManager startSurface(
      ViewManager<?, ?> viewManager, ThemedReactContext context, int surfaceId) {
    MountingManager mountingManager =
        new MountingManager(
            new ViewManagerRegistry(Collections.singletonList(viewManager)), items -> {});
    return mountingManager.startSurface(surfaceId, context, new ReactRootView(context));
  }
}
