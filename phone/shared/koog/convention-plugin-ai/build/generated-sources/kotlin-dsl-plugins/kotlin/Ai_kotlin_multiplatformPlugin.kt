/**
 * Precompiled [ai.kotlin.multiplatform.gradle.kts][Ai_kotlin_multiplatform_gradle] script plugin.
 *
 * @see Ai_kotlin_multiplatform_gradle
 */
public
class Ai_kotlin_multiplatformPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Ai_kotlin_multiplatform_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
