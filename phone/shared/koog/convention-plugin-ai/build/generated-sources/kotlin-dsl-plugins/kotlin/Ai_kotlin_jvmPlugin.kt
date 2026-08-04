/**
 * Precompiled [ai.kotlin.jvm.gradle.kts][Ai_kotlin_jvm_gradle] script plugin.
 *
 * @see Ai_kotlin_jvm_gradle
 */
public
class Ai_kotlin_jvmPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Ai_kotlin_jvm_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
