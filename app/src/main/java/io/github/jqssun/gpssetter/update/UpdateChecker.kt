package io.github.jqssun.gpssetter.update

import io.github.jqssun.gpssetter.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject


class UpdateChecker @Inject constructor(private val apiResponse : GitHubService) {

    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val response = apiResponse.getReleases().execute()
            if (!response.isSuccessful) {
                Timber.w("GitHub update check failed with HTTP %d", response.code())
                return@withContext CheckResult.Failed("GitHub returned HTTP ${response.code()}")
            }

            val release = response.body()
            val tagName = release?.tagName
            if (release == null || tagName == null) {
                Timber.w("GitHub latest release response is missing release data")
                return@withContext CheckResult.Failed("GitHub response did not contain a release tag")
            }

            if (tagName == "v${BuildConfig.TAG_NAME}") {
                return@withContext CheckResult.Current
            }

            val assetName = "app-${BuildConfig.FLAVOR}-arm64-v8a-release.apk"
            val assetUrl = release.assets
                ?.firstOrNull { it.name == assetName }
                ?.browserDownloadUrl
            if (assetUrl.isNullOrBlank()) {
                Timber.w("GitHub latest release is missing the expected APK: %s", assetName)
                return@withContext CheckResult.Failed("Release is missing $assetName")
            }

            CheckResult.Available(
                Update(
                    name = release.name ?: tagName,
                    changelog = release.body.orEmpty(),
                    assetUrl = assetUrl
                )
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Timber.e(exception, "Failed to check for updates on GitHub")
            CheckResult.Failed(
                exception.localizedMessage ?: exception.javaClass.simpleName
            )
        }
    }

    sealed class CheckResult {
        object Current : CheckResult()
        data class Failed(val reason: String) : CheckResult()
        data class Available(val update: Update) : CheckResult()
    }

    data class Update(val name: String, val changelog: String, val assetUrl: String)
}
