/**
 * Precompiled [ai.kotlin.multiplatform.server.gradle.kts][Ai_kotlin_multiplatform_server_gradle] script plugin.
 *
 * @see Ai_kotlin_multiplatform_server_gradle
 */
public
class Ai_kotlin_multiplatform_serverPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Ai_kotlin_multiplatform_server_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
