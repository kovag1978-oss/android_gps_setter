package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.LocationClusterIcon
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null
    private val activeMarkers = mutableListOf<Marker>()
    private val clusterMembers = mutableMapOf<Marker, List<ActiveMapLocation>>()
    private val spiderMarkers = mutableListOf<Marker>()
    private val spiderLines = mutableListOf<Polyline>()
    private var spiderExpanded = false
    private var appliedMapType: Int? = null

    override fun hasMarker(): Boolean {
        if (!mMarker?.isVisible!!) {
            return true
        }
        return false
    }
    private fun updateMarker(it: LatLng) {
        mMarker?.position = it!!
        mMarker?.isVisible = true
    }
    private fun removeMarker() {
        mMarker?.isVisible = false
    }
    override fun initializeMap() {
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }
    override fun refreshMapType() {
        if (!::mMap.isInitialized) return

        val selectedMapType = viewModel.mapType
        if (selectedMapType != appliedMapType) {
            mMap.mapType = selectedMapType
            appliedMapType = selectedMapType
        }
    }
    override fun refreshActiveLocations(locations: List<ActiveMapLocation>) {
        if (!::mMap.isInitialized) return

        clearSpider()
        activeMarkers.forEach(Marker::remove)
        activeMarkers.clear()
        clusterMembers.clear()
        val groups = groupMapLocations(locations) { location ->
            val point = mMap.projection.toScreenLocation(
                LatLng(location.latitude, location.longitude)
            )
            PointF(point.x.toFloat(), point.y.toFloat())
        }
        groups.forEach { group ->
            val center = clusterCenter(group)
            val options = MarkerOptions()
                .position(LatLng(center.latitude, center.longitude))
                .anchor(0.5f, if (group.size == 1) 1f else 0.5f)
            if (group.size == 1) {
                options.title(center.title)
                    .icon(BitmapDescriptorFactory.defaultMarker(center.colorHue))
            } else {
                options.title(group.joinToString { it.title })
                    .snippet(getString(R.string.location_cluster_count, group.size))
                    .icon(
                        BitmapDescriptorFactory.fromBitmap(
                            LocationClusterIcon.create(
                                group.map { it.colorHue },
                                group.size,
                                resources.displayMetrics.density
                            )
                        )
                    )
            }
            mMap.addMarker(options)?.let { marker ->
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
                Point(
                    (centerPoint.x + kotlin.math.cos(angle) * radius).toInt(),
                    (centerPoint.y + kotlin.math.sin(angle) * radius).toInt()
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
                    .icon(BitmapDescriptorFactory.defaultMarker(location.colorHue))
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
    private fun createMarkerDescriptor(color: Int): com.google.android.gms.maps.model.BitmapDescriptor {
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
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f)
                        .bearing(0f)
                        .tilt(0f)
                        .build()
                ))
                mMarker?.apply {
                    position = latLng
                    isVisible = true
                    showInfoWindow()
                }
            }
        }
    }
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        with(mMap){

            
            // gms custom ui
            if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { 
                setMyLocationEnabled(true); 
            } else {
                ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
            }
            setTrafficEnabled(true)
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isCompassEnabled = false
            setPadding(0,80,0,0)
            refreshMapType()


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                mMarker = addMarker(
                    MarkerOptions().position(it!!).draggable(false).icon(createMarkerDescriptor(Color.BLACK)).anchor(0.5f, 1f).visible(false)
                )
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))
            }

            
            setOnMapClickListener(this@MapActivity)
            setOnMarkerClickListener { marker ->
                val members = clusterMembers[marker]
                if (members != null) {
                    expandCluster(members)
                    true
                } else {
                    false
                }
            }
            setOnCameraIdleListener {
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
    override fun onMapClick(latLng: LatLng) {
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
