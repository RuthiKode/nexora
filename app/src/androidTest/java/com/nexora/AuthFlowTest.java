package com.nexora;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.firebase.auth.FirebaseAuth;
import com.nexora.data.SyncWorker;
import com.nexora.ui.AuthActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** UI smoke test of onboarding and auth validation (no backend needed). */
@RunWith(AndroidJUnit4.class)
public class AuthFlowTest {

    @Before public void freshInstall() {
        FirebaseAuth.getInstance().signOut();
        SyncWorker.prefs(ApplicationProvider.getApplicationContext()).edit().clear().commit();
    }

    @Test public void onboardingThenRegistrationValidation() {
        try (ActivityScenario<AuthActivity> ignored = ActivityScenario.launch(AuthActivity.class)) {
            onView(withText("Know where you stand")).check(matches(isDisplayed()));
            onView(withId(R.id.skip)).perform(click());
            onView(withId(R.id.mode_register)).perform(click());
            onView(withId(R.id.name_layout)).check(matches(isDisplayed()));
            onView(withId(R.id.email)).perform(replaceText("not-an-email"), closeSoftKeyboard());
            onView(withId(R.id.password)).perform(replaceText("short"), closeSoftKeyboard());
            onView(withId(R.id.submit)).perform(click());
            onView(withText("Enter a valid email address")).check(matches(isDisplayed()));
            onView(withText("Password must be at least 8 characters")).check(matches(isDisplayed()));
            onView(withText("Enter your name")).check(matches(isDisplayed()));
        }
    }
}
