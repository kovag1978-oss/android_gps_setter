package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.SupportMapFragment
import io.github.jqssun.gpssetter.utils.LocationClusterIcon

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, MapLibreMap.OnMapClickListener {

    private lateinit var mMap: MapLibreMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null
    private val activeMarkers = mutableListOf<Marker>()
    private val markerIcons = mutableMapOf<Int, Icon>()
    private val clusterMembers = mutableMapOf<Marker, List<ActiveMapLocation>>()
    private val spiderMarkers = mutableListOf<Marker>()
    private val spiderLines = mutableListOf<Polyline>()
    private var spiderExpanded = false
    private var appliedMapType: Int? = null

    override fun hasMarker(): Boolean {
        // TODO: if (!mMarker?.isVisible!!){
        if (mMarker != null) {
            return true
        }
        return false
    }
    private fun updateMarker(it: LatLng) {
        // TODO: mMarker?.isVisible = true
        if (mMarker == null) {
            mMarker = mMap.addMarker(
                MarkerOptions()
                    .position(it)
                    .icon(createMarkerIcon(Color.BLACK))
            )
        } else {
            mMarker?.position = it!!
        }
    }
    private fun createMarkerIcon(hue: Float): Icon =
        createMarkerIcon(Color.HSVToColor(floatArrayOf(hue, 1f, 0.9f)))

    private fun createMarkerIcon(color: Int): Icon =
        markerIcons.getOrPut(color) {
            val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val pin = Path().apply {
                moveTo(48f, 124f)
                cubicTo(39f, 108f, 7f, 72f, 7f, 46f)
                cubicTo(7f, 23f, 25f, 5f, 48f, 5f)
                cubicTo(71f, 5f, 89f, 23f, 89f, 46f)
                cubicTo(89f, 72f, 57f, 108f, 48f, 124f)
                close()
            }
            canvas.drawPath(pin, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            })
            canvas.drawCircle(48f, 46f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                style = Paint.Style.FILL
            })
            IconFactory.getInstance(this).fromBitmap(bitmap)
        }
    private fun createClusterIcon(group: List<ActiveMapLocation>): Icon =
        IconFactory.getInstance(this).fromBitmap(
            LocationClusterIcon.create(
                group.map { it.colorHue },
                group.size,
                resources.displayMetrics.density
            )
        )
    private fun removeMarker() {
        mMarker?.remove() // mMarker?.isVisible = false
        mMarker = null
    }
    override fun initializeMap() {
        val key = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData.getString("com.maplibre.AccessToken")
        MapLibre.getInstance(this, key, WellKnownTileServer.Mapbox)
        // val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }
    override fun refreshMapType() {
        if (!::mMap.isInitialized) return

        val selectedMapType = viewModel.mapType
        if (selectedMapType == appliedMapType) return

        appliedMapType = selectedMapType
        val typeUrl = when (selectedMapType) {
            2 -> "mapbox://styles/mapbox/satellite-streets-v12"
            3 -> "mapbox://styles/mapbox/outdoors-v12"
            4 -> "mapbox://styles/mapbox/navigation-day-v1"
            else -> "mapbox://styles/mapbox/streets-v12"
        }
        mMap.setStyle(typeUrl) { style ->
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val locationComponent = mMap.locationComponent
                locationComponent.activateLocationComponent(
                    LocationComponentActivationOptions.builder(this, style)
                        .useDefaultLocationEngine(true)
                        .build()
                )
                locationComponent.isLocationComponentEnabled = true
                locationComponent.cameraMode = CameraMode.TRACKING
                locationComponent.renderMode = RenderMode.COMPASS
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99)
            }
            refreshActiveLocations(getActiveMapLocations())
        }
    }
    override fun refreshActiveLocations(locations: List<ActiveMapLocation>) {
        if (!::mMap.isInitialized) return

        clearSpider()
        activeMarkers.forEach(Marker::remove)
        activeMarkers.clear()
        clusterMembers.clear()
        val groups = groupMapLocations(locations) { location ->
            mMap.projection.toScreenLocation(
                LatLng(location.latitude, location.longitude)
            )
        }
        groups.forEach { group ->
            val center = clusterCenter(group)
            val options = MarkerOptions()
                .position(LatLng(center.latitude, center.longitude))
                .title(group.joinToString { it.title })
            if (group.size == 1) {
                options.icon(createMarkerIcon(center.colorHue))
            } else {
                options.icon(createClusterIcon(group))
                    .snippet(getString(R.string.location_cluster_count, group.size))
            }
            mMap.addMarker(
                options
            )?.let { marker ->
                activeMarkers += marker
                if (group.size > 1) clusterMembers[marker] = group
            }
        }
    }
    private fun expandCluster(group: List<ActiveMapLocation>) {
        clearSpider()
        spiderExpanded = true
        val center = clusterCenter(group)
        val centerPoint = mMap.projection.toScreenLocation(LatLng(center.latitude, center.longitude))
        val density = resources.displayMetrics.density
        group.forEachIndexed { index, location ->
            val ring = index / 8
            val ringIndex = index % 8
            val ringSize = minOf(8, group.size - ring * 8)
            val radius = (64f + ring * 48f) * density
            val angle = -Math.PI / 2 + 2 * Math.PI * ringIndex / ringSize
            val expandedPosition = mMap.projection.fromScreenLocation(
                android.graphics.PointF(
                    (centerPoint.x + kotlin.math.cos(angle) * radius).toFloat(),
                    (centerPoint.y + kotlin.math.sin(angle) * radius).toFloat()
                )
            )
            spiderLines += mMap.addPolyline(
                PolylineOptions()
                    .add(LatLng(location.latitude, location.longitude), expandedPosition)
                    .color(Color.HSVToColor(floatArrayOf(location.colorHue, 1f, 0.9f)))
                    .width(2f)
            )
            mMap.addMarker(
                MarkerOptions()
                    .position(expandedPosition)
                    .title(location.title)
                    .icon(createMarkerIcon(location.colorHue))
            )?.let(spiderMarkers::add)
        }
    }

    private fun clearSpider() {
        spiderMarkers.forEach(Marker::remove)
        spiderMarkers.clear()
        spiderLines.forEach(Polyline::remove)
        spiderLines.clear()
        spiderExpanded = false
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                // mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng!!, 12.0f.toDouble()))
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f.toDouble())
                        .bearing(0f.toDouble())
                        .tilt(0f.toDouble())
                        .build()
                ))
                mMarker?.apply {
                    position = latLng
                    // TODO:
                    // isVisible = true
                    // showInfoWindow()
                }
            }
        }
    }
    override fun onMapReady(mapLibreMap: MapLibreMap) {
        mMap = mapLibreMap
        with(mMap){


            // maplibre custom ui
            refreshMapType()
            // TODO: fix bug with drawer
            uiSettings.setAllGesturesEnabled(true)
            uiSettings.setCompassEnabled(true)
            uiSettings.setCompassMargins(0,480,120,0)
            uiSettings.setLogoEnabled(true)
            uiSettings.setLogoMargins(0,0,0,80)
            uiSettings.setAttributionEnabled(false)
            // uiSettings.setAttributionMargins(80,0,0,80)
            // setPadding(0,0,0,80)


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                updateMarker(it!!)
                // TODO: MarkerOptions().position(it!!)
                // .draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED).visible(false)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom.toDouble()))
            }

            addOnMapClickListener(this@MapActivity)
            setOnMarkerClickListener { marker ->
                val members = clusterMembers[marker]
                if (members != null) {
                    expandCluster(members)
                    true
                } else {
                    false
                }
            }
            addOnCameraIdleListener {
                if (spiderExpanded) clearSpider()
                refreshActiveLocations(getActiveMapLocations())
            }
            if (viewModel.isStarted){
                mMarker?.let {
                    // TODO:
                    // it.isVisible = true
                    // it.showInfoWindow()
                }
            }
            refreshActiveLocations(getActiveMapLocations())
        }
    }
    override fun onMapClick(latLng: LatLng): Boolean {
        mLatLng = latLng
        mMarker?.let { marker ->
            mLatLng.let {
                // marker.isVisible = true
                updateMarker(it!!)
                mMap.animateCamera(CameraUpdateFactory.newLatLng(it))
                lat = it.latitude
                lon = it.longitude
                clearSelectedFavorite()
            }
        }
        return true
    }



    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            if (showManagedAppChoice()) {
                mLatLng?.let(::updateMarker)
                return@setOnClickListener
            }
            viewModel.update(true, lat, lon)
            mLatLng.let {
                updateMarker(it!!)
            }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            val favoriteName = selectedFavoriteName
            showStartNotification(
                favoriteName ?: getString(R.string.location_coordinates, lat, lon)
            )
            if (favoriteName == null) {
                lifecycleScope.launch {
                    mLatLng?.getAddress(getActivityInstance())?.let { address ->
                        address.collect { value ->
                            showStartNotification(
                                value.ifBlank { getString(R.string.location_coordinates, lat, lon) }
                            )
                        }
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            if (showRunningManagedLocationChoice { removeMarker() }) {
                return@setOnClickListener
            }
            mLatLng.let {
                viewModel.update(false, it!!.latitude, it.longitude)
            }
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }
}
