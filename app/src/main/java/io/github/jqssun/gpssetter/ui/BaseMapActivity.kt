package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.SQLException
import android.graphics.Color
import android.graphics.PointF
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.RelativeLayout
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.ElevationOverlayProvider
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.adapter.FavListAdapter
import io.github.jqssun.gpssetter.databinding.ActivityMapBinding
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.utils.FavoriteTextFormat
import io.github.jqssun.gpssetter.utils.JoystickService
import io.github.jqssun.gpssetter.utils.NotificationsChannel
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.ext.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.properties.Delegates
import timber.log.Timber

@AndroidEntryPoint
abstract class BaseMapActivity: AppCompatActivity() {

    private val SYSTEM_LOCATION_TARGET = "@system"
    protected var lat by Delegates.notNull<Double>()
    protected var lon by Delegates.notNull<Double>()
    protected var selectedFavoriteName: String? = null
        private set
    protected val viewModel by viewModels<MainViewModel>()
    protected val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    protected lateinit var alertDialog: MaterialAlertDialogBuilder
    protected lateinit var dialog: AlertDialog

    private val notificationsChannel by lazy { NotificationsChannel() }
    private var favListAdapter: FavListAdapter = FavListAdapter()
    private val exportFavoritesLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let(::exportFavorites)
        }
    private val importFavoritesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importFavorites)
        }
    private val notificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) showToast(getString(R.string.notifications_permission_required))
        }
    private var xposedDialog: AlertDialog? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val PERMISSION_ID = 42

    private val elevationOverlayProvider by lazy {
        ElevationOverlayProvider(this)
    }

    private val headerBackground by lazy {
        elevationOverlayProvider.compositeOverlayWithThemeSurfaceColorIfNeeded(
            resources.getDimension(R.dimen.bottom_sheet_elevation)
        )
    }

    protected abstract fun getActivityInstance(): BaseMapActivity
    protected abstract fun hasMarker(): Boolean
    protected abstract fun initializeMap()
    protected abstract fun refreshMapType()
    protected abstract fun refreshActiveLocations(locations: List<ActiveMapLocation>)
    protected abstract fun setupButtons()
    protected abstract fun moveMapToNewLocation(moveNewLocation: Boolean)
    protected abstract fun currentMapPoint(): MapPoint
    protected abstract fun moveFlightCamera(point: MapPoint, zoom: Double)
    protected abstract fun setSelectedMapPoint(point: MapPoint)
    protected abstract fun clearSelectedMapMarker()

    protected data class MapPoint(val latitude: Double, val longitude: Double)

    private data class SupportOption(
        val titleRes: Int,
        val iconRes: Int,
        val url: String
    )

    protected data class ActiveMapLocation(
        val title: String,
        val latitude: Double,
        val longitude: Double,
        val colorHue: Float
    )

    private var unregisterActiveLocationListener: (() -> Unit)? = null
    private var mapReady = false
    private var pendingMapResult: MapCommandResult? = null
    private var locationJob: Job? = null
    private var previewJob: Job? = null
    private var flightAnimator: ValueAnimator? = null
    private var flightFrom: MapPoint? = null
    private var flightTo: MapPoint? = null
    private var flightStartedAt = 0L
    private var restoreFlight = false
    private var activeResult: MapCommandResult? = null
    private var preview = false
    private var previewDeadline = 0L
    private val realLocationTracker by lazy { RealLocationTracker(this) }
    protected val isTemporaryPreview: Boolean get() = preview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = savedInstanceState?.getBoolean(STATE_PREVIEW)
            ?: intent.getBooleanExtra(EXTRA_PREVIEW, false)
        previewDeadline = savedInstanceState?.getLong(STATE_PREVIEW_DEADLINE) ?: 0L
        if (savedInstanceState == null) {
            readMapResult(intent)
        } else {
            val savedIntent = savedInstanceState.getParcelable<Intent>(STATE_RESULT)
            if (savedIntent != null) readMapResult(savedIntent)
            if (savedInstanceState.getBoolean(STATE_FLIGHT)) {
                flightFrom = MapPoint(savedInstanceState.getDouble(STATE_FROM_LAT), savedInstanceState.getDouble(STATE_FROM_LON))
                flightTo = MapPoint(savedInstanceState.getDouble(STATE_TO_LAT), savedInstanceState.getDouble(STATE_TO_LON))
                flightStartedAt = savedInstanceState.getLong(STATE_FLIGHT_START)
                restoreFlight = true
            }
        }
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launchWhenCreated {
            setContentView(binding.root)
        }
        setSupportActionBar(binding.toolbar)
        initializeMap()
        if (!preview) checkModuleEnabled()
        setupNavView()
        setupButtons()
        setupDrawer()
        if (!preview) requestNotificationsPermissionIfNeeded()
        if (PrefManager.isJoystickEnabled){
            startService(Intent(this, JoystickService::class.java))
        }
        if (!preview) promptRestartAfterAppUpdate()
        if (savedInstanceState != null && preview && previewDeadline != 0L &&
            pendingMapResult == null && !restoreFlight
        ) {
            startPreviewTimer()
        }
        if (preview) {
            lifecycleScope.launch {
                delay(15_000L)
                if (preview && !mapReady) {
                    showToast(getString(R.string.map_preview_unavailable))
                    startPreviewTimer()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        realLocationTracker.start()
        visibleMapCount++
        unregisterActiveLocationListener = PrefManager.registerActiveLocationChangeListener {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    refreshActiveLocations(getActiveMapLocations())
                }
            }
        }
    }

    override fun onStop() {
        visibleMapCount--
        realLocationTracker.stop()
        flightAnimator?.cancel()
        locationJob?.cancel()
        if (!isChangingConfigurations) {
            preview = false
            previewJob?.cancel()
        }
        unregisterActiveLocationListener?.invoke()
        unregisterActiveLocationListener = null
        super.onStop()
    }

    override fun onDestroy() {
        realLocationTracker.stop()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PREVIEW, preview)
        outState.putLong(STATE_PREVIEW_DEADLINE, previewDeadline)
        val result = pendingMapResult ?: activeResult
        if (result != null && (pendingMapResult != null || flightAnimator?.isRunning == true || locationJob?.isActive == true)) {
            outState.putParcelable(STATE_RESULT, mapResultIntent(result))
        }
        if (flightAnimator?.isRunning == true) {
            val from = flightFrom
            val to = flightTo
            if (from != null && to != null) {
                outState.putBoolean(STATE_FLIGHT, true)
                outState.putDouble(STATE_FROM_LAT, from.latitude)
                outState.putDouble(STATE_FROM_LON, from.longitude)
                outState.putDouble(STATE_TO_LAT, to.latitude)
                outState.putDouble(STATE_TO_LON, to.longitude)
                outState.putLong(STATE_FLIGHT_START, flightStartedAt)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        previewJob?.cancel()
        flightAnimator?.cancel()
        locationJob?.cancel()
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        previewDeadline = 0L
        readMapResult(intent)
        applyPendingMapResult()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            locationJob?.cancel()
            flightAnimator?.cancel()
            if (preview) {
                preview = false
                previewJob?.cancel()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun readMapResult(intent: Intent) {
        val previous = if (intent.hasExtra(EXTRA_PREVIOUS_LATITUDE) && intent.hasExtra(EXTRA_PREVIOUS_LONGITUDE)) {
            MapCoordinates(intent.getDoubleExtra(EXTRA_PREVIOUS_LATITUDE, 0.0), intent.getDoubleExtra(EXTRA_PREVIOUS_LONGITUDE, 0.0))
        } else null
        pendingMapResult = when {
            intent.getBooleanExtra(EXTRA_STOPPED, false) -> MapCommandResult.Stopped(previous)
            intent.hasExtra(EXTRA_LATITUDE) && intent.hasExtra(EXTRA_LONGITUDE) ->
                MapCommandResult.Applied(
                    intent.getDoubleExtra(EXTRA_LATITUDE, 0.0),
                    intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0),
                    previous
                )
            else -> null
        }
    }

    private fun mapResultIntent(result: MapCommandResult): Intent = Intent().apply {
        result.previous?.let {
            putExtra(EXTRA_PREVIOUS_LATITUDE, it.latitude)
            putExtra(EXTRA_PREVIOUS_LONGITUDE, it.longitude)
        }
        when (result) {
            is MapCommandResult.Applied -> {
                putExtra(EXTRA_LATITUDE, result.latitude)
                putExtra(EXTRA_LONGITUDE, result.longitude)
            }
            is MapCommandResult.Stopped -> putExtra(EXTRA_STOPPED, true)
        }
    }

    protected fun onMapInitialized() {
        mapReady = true
        findViewById<View>(R.id.map).doOnLayout {
            if (restoreFlight) {
                restoreFlight = false
                val result = pendingMapResult
                if (result is MapCommandResult.Applied) {
                    lat = result.latitude
                    lon = result.longitude
                    setSelectedMapPoint(MapPoint(lat, lon))
                    clearSelectedMapMarker()
                }
                activeResult = result
                pendingMapResult = null
                startFlight(requireNotNull(flightFrom), requireNotNull(flightTo), flightStartedAt)
            } else {
                applyPendingMapResult()
            }
        }
    }

    private fun applyPendingMapResult() {
        if (!mapReady || !ViewCompat.isLaidOut(findViewById(R.id.map))) return
        val result = pendingMapResult ?: return
        pendingMapResult = null
        activeResult = result
        locationJob?.cancel()
        flightAnimator?.cancel()
        val fallback = currentMapPoint()
        val remaining = getActiveMapLocations()
            .map { MapCoordinates(it.latitude, it.longitude) }
        when (val focus = mapFocusFor(result, remaining)) {
            is MapFocus.Applied -> {
                lat = focus.point.latitude
                lon = focus.point.longitude
                setSelectedMapPoint(MapPoint(lat, lon))
                clearSelectedMapMarker()
                val destination = MapPoint(lat, lon)
                val previous = result.previous
                if (previous != null) {
                    startFlight(MapPoint(previous.latitude, previous.longitude), destination)
                } else {
                    resolveRealLocation { real ->
                        startFlight(real ?: fallback, destination)
                    }
                }
            }
            is MapFocus.Remaining -> {
                val destination = focus.points.first().let { MapPoint(it.latitude, it.longitude) }
                val source = result.previous?.let { MapPoint(it.latitude, it.longitude) } ?: fallback
                startFlight(source, destination)
            }
            MapFocus.RealLocation -> resolveRealLocation { real ->
                if (real == null) showToast(getString(R.string.real_location_unavailable))
                val source = result.previous?.let { MapPoint(it.latitude, it.longitude) } ?: fallback
                startFlight(source, real ?: fallback)
            }
        }
    }

    protected fun showAppliedLocation(latitude: Double, longitude: Double, previous: MapCoordinates?) {
        pendingMapResult = MapCommandResult.Applied(latitude, longitude, previous)
        applyPendingMapResult()
    }

    protected fun trackedRealLocation(): MapCoordinates? = realLocationTracker.current()

    protected fun showStoppedLocation(previous: MapCoordinates?) {
        pendingMapResult = MapCommandResult.Stopped(previous)
        applyPendingMapResult()
    }

    private fun startFlight(from: MapPoint, to: MapPoint, startedAt: Long = SystemClock.elapsedRealtime()) {
        val map = findViewById<View>(R.id.map)
        flightFrom = from
        flightTo = to
        flightStartedAt = startedAt
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceIn(0L, FLIGHT_DURATION_MILLIS)
        val duration = FLIGHT_DURATION_MILLIS - elapsed
        if (from == to || duration == 0L) {
            moveFlightCamera(to, 16.0)
            startPreviewTimer()
            return
        }
        val flight = MapFlight(
            MapCoordinates(from.latitude, from.longitude),
            MapCoordinates(to.latitude, to.longitude),
            map.width, map.height
        )
        flightAnimator = ValueAnimator.ofFloat(elapsed.toFloat() / FLIGHT_DURATION_MILLIS, 1f).apply {
            this.duration = duration
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val frame = flight.frame(it.animatedValue as Float)
                moveFlightCamera(MapPoint(frame.latitude, frame.longitude), frame.zoom)
            }
            start()
        }
        startPreviewTimer()
    }

    private fun startPreviewTimer() {
        if (!preview) return
        if (previewDeadline == 0L) previewDeadline = SystemClock.elapsedRealtime() + FLIGHT_DURATION_MILLIS
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay((previewDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (preview && !isFinishing && !moveTaskToBack(true)) {
                Timber.w("Cannot move temporary map preview task to background")
                finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveRealLocation(onResult: (MapPoint?) -> Unit) {
        locationJob = lifecycleScope.launch {
            if (!checkPermissions() || !isLocationEnabled() ||
                (PrefManager.isStarted && PrefManager.isSystemHooked)
            ) {
                onResult(null)
                return@launch
            }
            // Let the system hook observe that global spoofing has stopped before requesting a fix.
            delay(500)
            val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val requestedAt = SystemClock.elapsedRealtimeNanos()
            val location = withTimeoutOrNull(10_000L) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (location.elapsedRealtimeNanos >= requestedAt &&
                                !location.isFromMockProvider && continuation.isActive
                            ) {
                                manager.removeUpdates(this)
                                continuation.resume(location)
                            }
                        }
                    }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                        .filter { manager.isProviderEnabled(it) }
                    try {
                        providers.forEach {
                            manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper())
                        }
                        if (providers.isEmpty()) continuation.resume(null)
                    } catch (exception: SecurityException) {
                        Timber.w(exception, "Cannot request real location")
                        manager.removeUpdates(listener)
                        if (continuation.isActive) continuation.resume(null)
                    } catch (exception: IllegalArgumentException) {
                        Timber.w(exception, "Cannot request real location")
                        manager.removeUpdates(listener)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
            onResult(location?.let { MapPoint(it.latitude, it.longitude) })
        }
    }

    companion object {
        private const val TRIBUTE_WEB_URL = "https://web.tribute.tg/d/V0z"
        private const val TRIBUTE_TELEGRAM_URL = "https://t.me/tribute/app?startapp=dV0z"
        private const val GITHUB_REPOSITORY_URL =
            "https://github.com/kovag1978-oss/android_gps_setter"
        private const val FLIGHT_DURATION_MILLIS = 4_000L
        internal const val EXTRA_PREVIEW = "map_preview"
        internal const val EXTRA_STOPPED = "map_stopped"
        internal const val EXTRA_LATITUDE = "map_latitude"
        internal const val EXTRA_LONGITUDE = "map_longitude"
        internal const val EXTRA_PREVIOUS_LATITUDE = "map_previous_latitude"
        internal const val EXTRA_PREVIOUS_LONGITUDE = "map_previous_longitude"
        private const val STATE_PREVIEW = "preview"
        private const val STATE_PREVIEW_DEADLINE = "preview_deadline"
        private const val STATE_RESULT = "flight_result"
        private const val STATE_FLIGHT = "flight_running"
        private const val STATE_FROM_LAT = "flight_from_lat"
        private const val STATE_FROM_LON = "flight_from_lon"
        private const val STATE_TO_LAT = "flight_to_lat"
        private const val STATE_TO_LON = "flight_to_lon"
        private const val STATE_FLIGHT_START = "flight_start"
        private var visibleMapCount = 0
        internal val isMapVisible: Boolean get() = visibleMapCount > 0
    }

    private fun requestNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun promptRestartAfterAppUpdate() {
        val preferences = getSharedPreferences("app_update_state", MODE_PRIVATE)
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val currentUpdateTime = packageInfo.lastUpdateTime
        val previousUpdateTime = preferences.getLong("last_update_time", 0L)
        preferences.edit().putLong("last_update_time", currentUpdateTime).apply()

        val wasUpdated = if (previousUpdateTime == 0L) {
            currentUpdateTime > packageInfo.firstInstallTime
        } else {
            currentUpdateTime > previousUpdateTime
        }
        if (wasUpdated) {
            binding.root.post {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.restart_after_update_title)
                    .setMessage(R.string.restart_after_update_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun setupDrawer() {
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val mDrawerToggle = object : ActionBarDrawerToggle(
            this,
            binding.container,
            binding.toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        ) {
            override fun onDrawerClosed(view: View) {
                super.onDrawerClosed(view)
                invalidateOptionsMenu()
            }

            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)
                invalidateOptionsMenu()
            }
        }
        binding.container.setDrawerListener(mDrawerToggle)
    }

    private fun setupNavView() {

        binding.mapContainer.map.setOnApplyWindowInsetsListener { _, insets ->
            val topInset: Int = insets.systemWindowInsetTop
            val bottomInset: Int = insets.systemWindowInsetBottom
            binding.navView.setPadding(0,topInset,0,0)
            insets.consumeSystemWindowInsets()
        }

        val progress = binding.search.searchProgress
        binding.search.searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                if (isNetworkConnected()) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val getInput = v.text.toString()
                        if (getInput.isNotEmpty()){
                            getSearchAddress(getInput).let {
                                it.collect { result ->
                                    when(result) {
                                        is SearchProgress.Progress -> {
                                            progress.visibility = View.VISIBLE
                                        }
                                        is SearchProgress.Complete -> {
                                            progress.visibility = View.GONE
                                            selectedFavoriteName = null
                                            lat = result.lat
                                            lon = result.lon
                                            moveMapToNewLocation(true)
                                        }
                                        is SearchProgress.Fail -> {
                                            progress.visibility = View.GONE
                                            showToast(result.error!!)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    showToast(getString(R.string.no_internet))
                }
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }

        binding.navView.setNavigationItemSelectedListener {
            when(it.itemId){
                R.id.get_favorite -> {
                    openFavoriteListDialog()
                }
                R.id.settings -> {
                    startActivity(Intent(this,ActivitySettings::class.java))
                }
                R.id.support_project -> {
                    showSupportOptionsDialog()
                }
                R.id.about -> {
                    aboutDialog()
                }
            }
            binding.container.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun showSupportOptionsDialog() {
        val options = listOf(
            SupportOption(
                R.string.support_tribute_web,
                R.drawable.ic_web,
                TRIBUTE_WEB_URL
            ),
            SupportOption(
                R.string.support_tribute_telegram,
                R.drawable.ic_telegram,
                TRIBUTE_TELEGRAM_URL
            ),
            SupportOption(
                R.string.support_github,
                R.drawable.ic_baseline_favorite,
                GITHUB_REPOSITORY_URL
            )
        )
        val adapter = object : ArrayAdapter<SupportOption>(
            this,
            R.layout.support_option_item,
            R.id.support_option_title,
            options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(
                    R.layout.support_option_item,
                    parent,
                    false
                )
                val option = getItem(position) ?: return view
                view.findViewById<ImageView>(R.id.support_option_icon)
                    .setImageResource(option.iconRes)
                view.findViewById<TextView>(R.id.support_option_title)
                    .setText(option.titleRes)
                return view
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.support_project)
            .setAdapter(adapter) { _, which ->
                options.getOrNull(which)?.let { openSupportUrl(it.url) }
            }
            .show()
    }

    private fun openSupportUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (exception: ActivityNotFoundException) {
            Timber.e(exception, "No app can open support URL: %s", url)
            showToast(getString(R.string.support_link_unavailable))
        }
    }

    private fun checkModuleEnabled(){
        viewModel.isXposed.observe(this) { isXposed ->
            xposedDialog?.dismiss()
            xposedDialog = null
            if (!isXposed) {
                xposedDialog = MaterialAlertDialogBuilder(this).run {
                    setTitle(R.string.error_xposed_module_missing)
                    setMessage(R.string.error_xposed_module_missing_desc)
                    // setCancelable(BuildConfig.DEBUG)
                    setCancelable(true)
                    show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMapType()
        viewModel.updateXposedState()
        updateLocationButtonVisibility()
        refreshActiveLocations(getActiveMapLocations())
        VoiceCommandTileService.requestStateUpdate(this)
    }

    protected fun getActiveMapLocations(): List<ActiveMapLocation> = buildList {
        if (PrefManager.isStarted) {
            add(
                ActiveMapLocation(
                    getString(R.string.voice_system_target),
                    PrefManager.getLat,
                    PrefManager.getLng,
                    0f
                )
            )
        }
        PrefManager.appLocationProfiles
            .filter { it.enabled && it.started }
            .sortedBy { it.packageName }
            .forEach { profile ->
                add(
                    ActiveMapLocation(
                        profile.appName,
                        profile.latitude,
                        profile.longitude,
                        profile.markerColorHue
                    )
                )
            }
    }

    protected fun groupMapLocations(
        locations: List<ActiveMapLocation>,
        screenPoint: (ActiveMapLocation) -> PointF
    ): List<List<ActiveMapLocation>> {
        val points = locations.map(screenPoint)
        val parents = IntArray(locations.size) { it }
        fun root(index: Int): Int {
            if (parents[index] != index) parents[index] = root(parents[index])
            return parents[index]
        }
        for (first in locations.indices) {
            for (second in first + 1 until locations.size) {
                val dx = points[first].x - points[second].x
                val dy = points[first].y - points[second].y
                if (dx * dx + dy * dy <= clusterDistancePx * clusterDistancePx) {
                    parents[root(second)] = root(first)
                }
            }
        }
        return locations.indices
            .groupBy(::root)
            .values
            .map { indices -> indices.map(locations::get) }
    }

    protected fun clusterCenter(locations: List<ActiveMapLocation>): ActiveMapLocation =
        locations.first().copy(
            latitude = locations.map { it.latitude }.average(),
            longitude = locations.map { it.longitude }.average()
        )

    private val clusterDistancePx: Float
        get() = 88f * resources.displayMetrics.density

    protected fun applyLocationToManagedApps(packageName: String? = null): Boolean {
        if (!PrefManager.isSeparateControlEnabled) return false

        val profiles = PrefManager.appLocationProfiles.filter { it.enabled }
        val includeSystem = packageName == null || packageName == SYSTEM_LOCATION_TARGET
        val targets = if (packageName == null) {
            profiles
        } else if (includeSystem) {
            emptyList()
        } else {
            profiles.filter { it.packageName == packageName }
        }
        if (targets.isEmpty() && !includeSystem) {
            showToast(getString(R.string.app_profile_unavailable))
            return true
        }

        val targetPackages = targets.map { it.packageName }.toSet()
        val previous = activeLocationForTargets(
            if (includeSystem) targetPackages + SYSTEM_TARGET else targetPackages
        )
            ?: trackedRealLocation()
        try {
            PrefManager.applyLocations(targetPackages, includeSystem, lat, lon)
        } catch (exception: IllegalStateException) {
            Timber.e(exception, "Failed to apply locations")
            showToast(getString(R.string.location_apply_failed))
            return true
        }
        showAppliedLocation(lat, lon, previous)
        val locationName = selectedFavoriteName
            ?: getString(R.string.location_coordinates, lat, lon)
        if (includeSystem) showStartNotification(locationName)
        if (targets.isNotEmpty()) {
            showManagedStartNotifications(targets.map { profile ->
                profile.packageName to
                    getString(R.string.location_applied_to_app, profile.appName, locationName)
            })
        }
        val toastText = if (includeSystem && packageName == null) {
            getString(R.string.location_applied_to_system_and_apps, targets.size, locationName)
        } else if (includeSystem) {
            getString(R.string.location_applied_to_app, getString(R.string.voice_system_target), locationName)
        } else {
            getString(R.string.location_applied_to_app, targets.single().appName, locationName)
        }
        updateLocationButtonVisibility()
        VoiceCommandTileService.requestStateUpdate(this)
        showToast(toastText)
        return true
    }

    protected fun showManagedAppChoice(): Boolean {
        if (!PrefManager.isSeparateControlEnabled) return false

        val profiles = PrefManager.appLocationProfiles
            .filter { it.enabled }
            .sortedBy { it.appName.lowercase(java.util.Locale.getDefault()) }
        val choices = listOf(getString(R.string.all_managed_apps), getString(R.string.voice_system_target)) +
            profiles.map { it.appName }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_managed_app)
            .setItems(choices.toTypedArray()) { _, index ->
                if (index == 0) {
                    applyLocationToManagedApps()
                } else if (index == 1) {
                    applyLocationToManagedApps(SYSTEM_LOCATION_TARGET)
                } else {
                    applyLocationToManagedApps(profiles[index - 2].packageName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    protected fun showRunningManagedLocationChoice(onStopAll: (() -> Unit)? = null): Boolean {
        if (!PrefManager.isSeparateControlEnabled) return false

        val activeProfiles = PrefManager.appLocationProfiles
            .filter { it.enabled && it.started }
            .sortedBy { it.appName.lowercase(java.util.Locale.getDefault()) }
        val targets = activeProfiles.map { it.packageName to it.appName }.toMutableList()
        if (PrefManager.isStarted) {
            targets += SYSTEM_LOCATION_TARGET to getString(R.string.voice_system_target)
        }
        if (targets.isEmpty()) {
            showToast(getString(R.string.no_managed_locations_running))
            return true
        }

        val choices = listOf(getString(R.string.all_managed_apps)) +
            targets.map { it.second }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_managed_location_to_stop)
            .setItems(choices.toTypedArray()) { _, index ->
                if (index == 0) {
                    if (stopManagedLocations()) onStopAll?.invoke()
                } else {
                    stopManagedLocation(targets[index - 1].first)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    protected fun stopManagedLocations(): Boolean {
        if (!PrefManager.isSeparateControlEnabled) return false

        val previous = activeLocationForTargets(allActiveTargets())
        PrefManager.appLocationProfiles.forEach {
            cancelManagedLocationNotification(it.packageName)
        }
        PrefManager.stopAllAppLocations()
        if (PrefManager.isStarted) {
            viewModel.update(false, PrefManager.getLat, PrefManager.getLng)
        }
        cancelNotification()
        updateLocationButtonVisibility()
        VoiceCommandTileService.requestStateUpdate(this)
        showToast(getString(R.string.managed_locations_stopped))
        showStoppedLocation(previous)
        return true
    }

    private fun stopManagedLocation(packageName: String) {
        val previous = activeLocationForTargets(setOf(packageName))
        if (packageName == SYSTEM_LOCATION_TARGET) {
            viewModel.update(false, PrefManager.getLat, PrefManager.getLng)
            cancelNotification()
        } else {
            PrefManager.setAppLocation(packageName, start = false)
            cancelManagedLocationNotification(packageName)
        }
        updateLocationButtonVisibility()
        VoiceCommandTileService.requestStateUpdate(this)
        showToast(getString(R.string.managed_location_stopped))
        showStoppedLocation(previous)
    }

    private fun updateLocationButtonVisibility() {
        val separateControl = PrefManager.isSeparateControlEnabled
        if (separateControl) {
            binding.startButton.visibility = View.VISIBLE
            binding.stopButton.visibility =
                if (PrefManager.isStarted || PrefManager.hasActiveAppProfile) View.VISIBLE else View.GONE
            (binding.stopButton.layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                params.removeRule(RelativeLayout.ABOVE)
                params.addRule(RelativeLayout.ABOVE, binding.startButton.id)
                binding.stopButton.layoutParams = params
            }
        } else {
            binding.startButton.visibility = if (PrefManager.isStarted) View.GONE else View.VISIBLE
            binding.stopButton.visibility = if (PrefManager.isStarted) View.VISIBLE else View.GONE
            (binding.stopButton.layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                params.removeRule(RelativeLayout.ABOVE)
                params.addRule(RelativeLayout.ABOVE, binding.addfavorite.id)
                binding.stopButton.layoutParams = params
            }
        }
    }

    protected fun aboutDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        layoutInflater.inflate(R.layout.about,null).apply {
            val  titlele = findViewById<TextView>(R.id.design_about_title)
            val  version = findViewById<TextView>(R.id.design_about_version)
            val  info = findViewById<TextView>(R.id.design_about_info)
            titlele.text = getString(R.string.app_name)
            version.text = BuildConfig.VERSION_NAME
            info.text = getString(R.string.about_info)
        }.run {
            alertDialog.setView(this)
            alertDialog.show()
        }
    }

    protected fun addFavoriteDialog() {
        alertDialog =  MaterialAlertDialogBuilder(this).apply {
            val view = layoutInflater.inflate(R.layout.dialog,null)
            val editText = view.findViewById<EditText>(R.id.search_edittxt)
            setTitle(getString(R.string.add_fav_dialog_title))
            setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                val s = editText.text.toString()
                if (hasMarker()){
                  showToast(getString(R.string.location_not_select))
                }else{
                    viewModel.storeFavorite(s, lat, lon)
                    viewModel.response.observe(getActivityInstance()){
                        if (it == (-1).toLong()) showToast(getString(R.string.cant_save)) else showToast(getString(R.string.save))
                    }
                }
            }
            setView(view)
            show()
        }
    }

    private fun openFavoriteListDialog() {
        getAllUpdatedFavList()
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(getString(R.string.favorites))
        val view = layoutInflater.inflate(R.layout.fav,null)
        val rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        var pendingFavoriteSelection: Runnable? = null
        favListAdapter.onItemClick = { favorite ->
            favListAdapter.selectFavorite(favorite)
            pendingFavoriteSelection?.let(rcv::removeCallbacks)
            pendingFavoriteSelection = Runnable {
                lat = favorite.lat!!
                lon = favorite.lng!!
                selectedFavoriteName = favorite.address?.takeIf(String::isNotBlank)
                moveMapToNewLocation(true)
                if (dialog.isShowing) dialog.dismiss()
            }.also { rcv.postDelayed(it, 600L) }
        }
        favListAdapter.onItemDelete = {
            viewModel.deleteFavorite(it)
        }
        favListAdapter.onItemRename = { favorite ->
            val renameView = layoutInflater.inflate(R.layout.dialog, null)
            val nameInput = renameView.findViewById<EditText>(R.id.search_edittxt).apply {
                hint = getString(R.string.favorite_name)
                setText(favorite.address.orEmpty())
                setSelection(text.length)
            }
            val renameDialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rename_favorite)
                .setView(renameView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rename, null)
                .create()
            renameDialog.setOnShowListener {
                renameDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = nameInput.text.toString().trim()
                    if (name.isEmpty()) {
                        nameInput.error = getString(R.string.favorite_name_required)
                    } else {
                        viewModel.updateFavorite(favorite.copy(address = name))
                        renameDialog.dismiss()
                    }
                }
            }
            renameDialog.show()
        }
        alertDialog.setView(view)
        dialog = alertDialog.create()
        dialog.show()
        view.findViewById<View>(R.id.export_favorites).setOnClickListener {
            exportFavoritesLauncher.launch("favorites.txt")
        }
        view.findViewById<View>(R.id.import_favorites).setOnClickListener {
            importFavoritesLauncher.launch(arrayOf("text/*"))
        }

    }

    private fun exportFavorites(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = FavoriteTextFormat.encode(viewModel.getFavoritesSnapshot())
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Could not open the selected file")
                    output.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(text)
                    }
                }
                showToast(getString(R.string.favorites_exported))
            } catch (exception: IOException) {
                showToast(getString(R.string.favorites_export_failed))
            } catch (exception: SecurityException) {
                showToast(getString(R.string.favorites_export_failed))
            } catch (exception: IllegalStateException) {
                showToast(getString(R.string.favorites_export_failed))
            } catch (exception: SQLException) {
                showToast(getString(R.string.favorites_export_failed))
            }
        }
    }

    private fun importFavorites(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open the selected file")
                    input.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
                }
                val favorites = FavoriteTextFormat.decode(text)
                if (favorites.isEmpty()) {
                    showToast(getString(R.string.favorites_import_empty))
                } else {
                    val (imported, skipped) = viewModel.importFavorites(favorites)
                    showToast(getString(R.string.favorites_imported, imported, skipped))
                }
            } catch (exception: IOException) {
                showToast(getString(R.string.favorites_import_failed, exception.localizedMessage.orEmpty()))
            } catch (exception: SecurityException) {
                showToast(getString(R.string.favorites_import_failed, exception.localizedMessage.orEmpty()))
            } catch (exception: IllegalArgumentException) {
                showToast(getString(R.string.favorites_file_invalid))
            } catch (exception: SQLException) {
                showToast(getString(R.string.favorites_import_failed, exception.localizedMessage.orEmpty()))
            } catch (exception: IllegalStateException) {
                showToast(getString(R.string.favorites_import_failed, exception.localizedMessage.orEmpty()))
            }
        }
    }

    private fun getAllUpdatedFavList(){
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
                viewModel.doGetUserDetails()
                viewModel.allFavList.collect {
                    favListAdapter.submitList(it)
                }
            }
        }

    }

    private suspend fun getSearchAddress(address: String) = callbackFlow {
        withContext(Dispatchers.IO){
            trySend(SearchProgress.Progress)
            val matcher: Matcher =
                Pattern.compile("[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?").matcher(address)

            if (matcher.matches()){
                delay(3000)
                trySend(SearchProgress.Complete(matcher.group().split(",")[0].toDouble(),matcher.group().split(",")[1].toDouble()))
            }else {
                val geocoder = Geocoder(getActivityInstance())
                val addressList: List<Address>? = geocoder.getFromLocationName(address,3)

                try {
                    addressList?.let {
                        if (it.size == 1){
                           trySend(SearchProgress.Complete(addressList[0].latitude, addressList[0].longitude))
                        }else {
                            trySend(SearchProgress.Fail(getString(R.string.address_not_found)))
                        }
                    }
                } catch (io : IOException){
                    trySend(SearchProgress.Fail(getString(R.string.no_internet)))
                }
            }
        }
        awaitClose { this.cancel() }
    }

    protected fun showStartNotification(address: String){
        if (!canShowLocationNotifications()) {
            showToast(getString(R.string.notifications_permission_required))
            return
        }
        notificationsChannel.showLocationNotification(this, address)
    }

    private fun showManagedStartNotifications(notifications: List<Pair<String, String>>) {
        if (!canShowLocationNotifications()) {
            showToast(getString(R.string.notifications_permission_required))
            return
        }
        notifications.forEach { (packageName, address) ->
            notificationsChannel.showLocationNotification(this, packageName, address)
        }
    }

    private fun canShowLocationNotifications(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    protected fun clearSelectedFavorite() {
        selectedFavoriteName = null
    }

    protected fun cancelNotification(){
        notificationsChannel.cancelLocationNotification(this)
    }

    private fun cancelManagedLocationNotification(packageName: String) {
        notificationsChannel.cancelManagedLocationNotification(this, packageName)
    }

    // Get current location
    @SuppressLint("MissingPermission")
    protected fun getLastLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (checkPermissions()) {
            if (isLocationEnabled()) {
                fusedLocationClient.lastLocation.addOnCompleteListener(this) { task ->
                    val location: Location? = task.result
                    if (location == null) {
                        requestNewLocationData()
                    } else {
                        selectedFavoriteName = null
                        lat = location.latitude
                        lon = location.longitude
                        moveMapToNewLocation(true)
                    }
                }
            } else {
                showToast(getString(R.string.turn_on_location))
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            }
        } else {
            requestPermissions()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData() {
        val mLocationRequest = LocationRequest()
        mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        mLocationRequest.interval = 0
        mLocationRequest.fastestInterval = 0
        mLocationRequest.numUpdates = 1

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.requestLocationUpdates(
            mLocationRequest, mLocationCallback,
            Looper.myLooper()
        )
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation!!
            lat = mLastLocation.latitude
            lon = mLastLocation.longitude
            selectedFavoriteName = null
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager: LocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
            LocationManager.NETWORK_PROVIDER
        )
    }

    private fun checkPermissions(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return false
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            PERMISSION_ID
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
            realLocationTracker.start()
            getLastLocation()
        }
    }
}

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double , val lon : Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}
