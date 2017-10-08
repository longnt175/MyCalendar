package com.simplemobiletools.calendar.activities

import android.content.Intent
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.support.v4.view.ViewPager
import android.util.SparseIntArray
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.adapters.MyMonthPagerAdapter
import com.simplemobiletools.calendar.adapters.MyWeekPagerAdapter
import com.simplemobiletools.calendar.dialogs.FilterEventTypesDialog
import com.simplemobiletools.calendar.extensions.*
import com.simplemobiletools.calendar.fragments.EventListFragment
import com.simplemobiletools.calendar.fragments.WeekFragment
import com.simplemobiletools.calendar.helpers.*
import com.simplemobiletools.calendar.interfaces.NavigationListener
import com.simplemobiletools.calendar.views.MyScrollView
import com.simplemobiletools.commons.dialogs.RadioGroupDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.models.RadioItem
import kotlinx.android.synthetic.main.activity_main.*
import org.joda.time.DateTime

class MainActivity : SimpleActivity(), NavigationListener {
    private val CALDAV_SYNC_DELAY = 2000L
    private val PREFILLED_MONTHS = 97
    private val PREFILLED_YEARS = 31
    private val PREFILLED_WEEKS = 61

    private var mIsMonthSelected = false
    private var mStoredTextColor = 0
    private var mStoredBackgroundColor = 0
    private var mStoredPrimaryColor = 0
    private var mStoredDayCode = ""
    private var mStoredIsSundayFirst = false
    private var mStoredUse24HourFormat = false
    private var mShouldFilterBeVisible = false
    private var mCalDAVSyncHandler = Handler()

    private var mDefaultWeeklyPage = 0
    private var mDefaultMonthlyPage = 0
    private var mDefaultYearlyPage = 0

    companion object {
        var mWeekScrollY = 0
        var eventTypeColors = SparseIntArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        calendar_fab.setOnClickListener { launchNewEventIntent() }
        storeStoragePaths()
        if (resources.getBoolean(R.bool.portrait_only))
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        storeStateVariables()
        updateViewPager()


        recheckCalDAVCalendars {}

        if (config.googleSync) {
            val ids = dbHelper.getGoogleSyncEvents().map { it.id.toString() }.toTypedArray()
            dbHelper.deleteEvents(ids, false)
            config.googleSync = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (mStoredTextColor != config.textColor || mStoredBackgroundColor != config.backgroundColor || mStoredPrimaryColor != config.primaryColor
                || mStoredDayCode != getCurrentDayCode()) {
            updateViewPager()
        }

        dbHelper.getEventTypes {
            eventTypeColors.clear()
            it.map { eventTypeColors.put(it.id, it.color) }
            mShouldFilterBeVisible = eventTypeColors.size() > 1 || config.displayEventTypes.isEmpty()
            invalidateOptionsMenu()
        }

        storeStateVariables()
        if (config.storedView == WEEKLY_VIEW) {
            if (mStoredIsSundayFirst != config.isSundayFirst || mStoredUse24HourFormat != config.use24hourFormat) {
                fillWeeklyViewPager()
            }
        }

        updateWidgets()
        if (config.storedView != EVENTS_LIST_VIEW)
            updateTextColors(calendar_coordinator)
    }

    override fun onPause() {
        super.onPause()
        mStoredTextColor = config.textColor
        mStoredIsSundayFirst = config.isSundayFirst
        mStoredBackgroundColor = config.backgroundColor
        mStoredPrimaryColor = config.primaryColor
        mStoredUse24HourFormat = config.use24hourFormat
    }

    override fun onStop() {
        super.onStop()
        mCalDAVSyncHandler.removeCallbacksAndMessages(null)
        contentResolver.unregisterContentObserver(calDAVSyncObserver)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.filter).isVisible = mShouldFilterBeVisible
        menu.findItem(R.id.go_to_today).isVisible = shouldGoToTodayBeVisible()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.change_view -> showViewDialog()
            R.id.go_to_today -> goToToday()
            R.id.filter -> showFilterDialog()
            R.id.settings -> launchSettings()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    private fun storeStateVariables() {
        mStoredTextColor = config.textColor
        mStoredPrimaryColor = config.primaryColor
        mStoredBackgroundColor = config.backgroundColor
        mStoredDayCode = getCurrentDayCode()
    }

    private fun getCurrentDayCode() = Formatter.getDayCodeFromTS((System.currentTimeMillis() / 1000).toInt())

    private fun showViewDialog() {
        val res = resources
        val items = arrayListOf(
                RadioItem(WEEKLY_VIEW, res.getString(R.string.weekly_view)),
                RadioItem(MONTHLY_VIEW, res.getString(R.string.monthly_view)),
                RadioItem(EVENTS_LIST_VIEW, res.getString(R.string.simple_event_list)))

        RadioGroupDialog(this, items, config.storedView) {
            updateView(it as Int)
            invalidateOptionsMenu()
        }
    }

    private fun goToToday() {
        if (config.storedView == WEEKLY_VIEW) {
            week_view_view_pager.currentItem = mDefaultWeeklyPage
        } else if (config.storedView == MONTHLY_VIEW) {
            main_view_pager.currentItem = mDefaultMonthlyPage
        }
    }

    private fun shouldGoToTodayBeVisible() = when {
        config.storedView == WEEKLY_VIEW -> week_view_view_pager.currentItem != mDefaultWeeklyPage
        config.storedView == MONTHLY_VIEW -> main_view_pager.currentItem != mDefaultMonthlyPage
        else -> false
    }

    private fun showFilterDialog() {
        FilterEventTypesDialog(this) {
            refreshViewPager()
        }
    }

    private val calDAVSyncObserver = object : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            if (!selfChange) {
                mCalDAVSyncHandler.removeCallbacksAndMessages(null)
                mCalDAVSyncHandler.postDelayed({
                    recheckCalDAVCalendars {
                        refreshViewPager()
                        toast(R.string.refreshing_complete)
                    }
                }, CALDAV_SYNC_DELAY)
            }
        }
    }


    private fun updateView(view: Int) {
        mIsMonthSelected = view == MONTHLY_VIEW
        config.storedView = view
        updateViewPager()
    }

    private fun updateViewPager() {
        resetTitle()
        when {
            config.storedView == EVENTS_LIST_VIEW -> fillEventsList()
            config.storedView == WEEKLY_VIEW -> fillWeeklyViewPager()
            else -> openMonthlyToday()
        }

        mWeekScrollY = 0
    }

    private fun openMonthlyToday() {
        val targetDay = DateTime().toString(Formatter.DAYCODE_PATTERN)
        fillMonthlyViewPager(targetDay)
    }

    private fun refreshViewPager() {
        when {
            config.storedView == EVENTS_LIST_VIEW -> fillEventsList()
            config.storedView == WEEKLY_VIEW -> (week_view_view_pager.adapter as MyWeekPagerAdapter).refreshEvents(week_view_view_pager.currentItem)
            else -> (main_view_pager.adapter as MyMonthPagerAdapter).refreshEvents(main_view_pager.currentItem)
        }
    }


    private fun launchSettings() {
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }


    private fun resetTitle() {
        title = getString(R.string.app_launcher_name)
        supportActionBar?.subtitle = ""
    }

    private fun fillMonthlyViewPager(targetDay: String) {
        main_weekly_scrollview.beGone()
        calendar_fab.beVisible()
        val codes = getMonths(targetDay)
        val monthlyAdapter = MyMonthPagerAdapter(supportFragmentManager, codes, this)
        mDefaultMonthlyPage = codes.size / 2

        main_view_pager.apply {
            adapter = monthlyAdapter
            beVisible()
            addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) {
                }

                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                }

                override fun onPageSelected(position: Int) {
                    invalidateOptionsMenu()
                }
            })
            currentItem = mDefaultMonthlyPage
        }
        calendar_event_list_holder.beGone()
    }

    private fun getMonths(code: String): List<String> {
        val months = ArrayList<String>(PREFILLED_MONTHS)
        val today = Formatter.getDateTimeFromCode(code)
        for (i in -PREFILLED_MONTHS / 2..PREFILLED_MONTHS / 2) {
            months.add(Formatter.getDayCodeFromDateTime(today.plusMonths(i)))
        }

        return months
    }

    private fun fillWeeklyViewPager() {
        var thisweek = DateTime().withDayOfWeek(1).withTimeAtStartOfDay().minusDays(if (config.isSundayFirst) 1 else 0)
        if (DateTime().minusDays(7).seconds() > thisweek.seconds()) {
            thisweek = thisweek.plusDays(7)
        }
        val weekTSs = getWeekTimestamps(thisweek.seconds())
        val weeklyAdapter = MyWeekPagerAdapter(supportFragmentManager, weekTSs, object : WeekFragment.WeekScrollListener {
            override fun scrollTo(y: Int) {
                week_view_hours_scrollview.scrollY = y
                mWeekScrollY = y
            }
        })
        main_view_pager.beGone()
        calendar_event_list_holder.beGone()
        main_weekly_scrollview.beVisible()

        week_view_hours_holder.removeAllViews()
        val hourDateTime = DateTime().withDate(2000, 1, 1).withTime(0, 0, 0, 0)
        for (i in 1..23) {
            val formattedHours = Formatter.getHours(this, hourDateTime.withHourOfDay(i))
            (layoutInflater.inflate(R.layout.weekly_view_hour_textview, null, false) as TextView).apply {
                text = formattedHours
                setTextColor(mStoredTextColor)
                week_view_hours_holder.addView(this)
            }
        }

        mDefaultWeeklyPage = weekTSs.size / 2
        week_view_view_pager.apply {
            adapter = weeklyAdapter
            addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) {
                }

                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                }

                override fun onPageSelected(position: Int) {
                    invalidateOptionsMenu()
                    setupWeeklyActionbarTitle(weekTSs[position])
                }
            })
            currentItem = mDefaultWeeklyPage
        }

        week_view_hours_scrollview.setOnScrollviewListener(object : MyScrollView.ScrollViewListener {
            override fun onScrollChanged(scrollView: MyScrollView, x: Int, y: Int, oldx: Int, oldy: Int) {
                mWeekScrollY = y
                weeklyAdapter.updateScrollY(week_view_view_pager.currentItem, y)
            }
        })
        week_view_hours_scrollview.setOnTouchListener({ view, motionEvent -> true })
    }

    fun updateHoursTopMargin(margin: Int) {
        week_view_hours_divider.layoutParams.height = margin
        week_view_hours_scrollview.requestLayout()
    }

    private fun getWeekTimestamps(targetWeekTS: Int): List<Int> {
        val weekTSs = ArrayList<Int>(PREFILLED_WEEKS)
        for (i in -PREFILLED_WEEKS / 2..PREFILLED_WEEKS / 2) {
            weekTSs.add(Formatter.getDateTimeFromTS(targetWeekTS).plusWeeks(i).seconds())
        }
        return weekTSs
    }

    private fun setupWeeklyActionbarTitle(timestamp: Int) {
        val startDateTime = Formatter.getDateTimeFromTS(timestamp)
        val endDateTime = Formatter.getDateTimeFromTS(timestamp + WEEK_SECONDS)
        val startMonthName = Formatter.getMonthName(this, startDateTime.monthOfYear)
        if (startDateTime.monthOfYear == endDateTime.monthOfYear) {
            var newTitle = startMonthName
            if (startDateTime.year != DateTime().year)
                newTitle += " - ${startDateTime.year}"
            title = newTitle
        } else {
            val endMonthName = Formatter.getMonthName(this, endDateTime.monthOfYear)
            title = "$startMonthName - $endMonthName"
        }
        supportActionBar?.subtitle = "${getString(R.string.week)} ${startDateTime.plusDays(3).weekOfWeekyear}"
    }

    private fun getYears(targetYear: Int): List<Int> {
        val years = ArrayList<Int>(PREFILLED_YEARS)
        years += targetYear - PREFILLED_YEARS / 2..targetYear + PREFILLED_YEARS / 2
        return years
    }

    private fun fillEventsList() {
        main_view_pager.adapter = null
        main_view_pager.beGone()
        main_weekly_scrollview.beGone()
        calendar_event_list_holder.beVisible()
        supportFragmentManager.beginTransaction().replace(R.id.calendar_event_list_holder, EventListFragment(), "").commit()
    }

    override fun goLeft() {
        main_view_pager.currentItem = main_view_pager.currentItem - 1
    }

    override fun goRight() {
        main_view_pager.currentItem = main_view_pager.currentItem + 1
    }

    override fun goToDateTime(dateTime: DateTime) {
        fillMonthlyViewPager(Formatter.getDayCodeFromDateTime(dateTime))
        mIsMonthSelected = true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    }


}
