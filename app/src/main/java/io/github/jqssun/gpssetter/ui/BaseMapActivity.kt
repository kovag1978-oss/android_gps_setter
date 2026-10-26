package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.SQLException
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.view.View
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
import kotlin.properties.Delegates

@AndroidEntryPoint
abstract class BaseMapActivity: AppCompatActivity() {

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
    protected abstract fun setupButtons()
    protected abstract fun moveMapToNewLocation(moveNewLocation: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launchWhenCreated {
            setContentView(binding.root)
        }
        setSupportActionBar(binding.toolbar)
        initializeMap()
        checkModuleEnabled()
        setupNavView()
        setupButtons()
        setupDrawer()
        requestNotificationsPermissionIfNeeded()
        if (PrefManager.isJoystickEnabled){
            startService(Intent(this, JoystickService::class.java))
        }
        promptRestartAfterAppUpdate()
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
                R.id.about -> {
                    aboutDialog()
                }
            }
            binding.container.closeDrawer(GravityCompat.START)
            true
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
        binding.startButton.visibility = if (PrefManager.isStarted) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (PrefManager.isStarted) View.VISIBLE else View.GONE
        VoiceCommandTileService.requestStateUpdate(this)
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
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        ) {
            showToast(getString(R.string.notifications_permission_required))
            return
        }
        notificationsChannel.showLocationNotification(this, address)
    }

    protected fun clearSelectedFavorite() {
        selectedFavoriteName = null
    }

    protected fun cancelNotification(){
        notificationsChannel.cancelLocationNotification(this)
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
            getLastLocation()
        }
    }
}

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double , val lon : Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}
