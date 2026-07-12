/**
 * Gameplay features, one subpackage per feature.
 *
 * <p>Convention for adding a feature:
 * <ol>
 *   <li>Create {@code feature/<featurename>/} and keep everything the feature
 *       needs (event handlers, custom item/block classes, logic) inside it.</li>
 *   <li>Register content through the classes in {@code registry/} — features
 *       never create their own {@code DeferredRegister}.</li>
 *   <li>Event-driven logic uses {@code @EventBusSubscriber} so it is picked up
 *       automatically (see {@code feature/greeting} for the pattern).</li>
 *   <li>Gate behavior behind a {@code Config} option when it could be
 *       controversial on servers.</li>
 * </ol>
 */
package dev.nez.allunderheaven.feature;
