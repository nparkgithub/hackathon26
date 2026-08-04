/**
 * Precompiled [ai.kotlin.jvm.publish.gradle.kts][Ai_kotlin_jvm_publish_gradle] script plugin.
 *
 * @see Ai_kotlin_jvm_publish_gradle
 */
public
class Ai_kotlin_jvm_publishPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Ai_kotlin_jvm_publish_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
