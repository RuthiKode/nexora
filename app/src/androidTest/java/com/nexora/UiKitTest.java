package com.nexora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.view.ContextThemeWrapper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.nexora.domain.Validators;
import com.nexora.ui.UiKit;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression: field errors must clear as soon as the input becomes valid, not on the next save. */
@RunWith(AndroidJUnit4.class)
public class UiKitTest {

    @Test public void revalidateClearsErrorOnlyWhenInputBecomesValid() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context ctx = new ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_NEXORA);
            TextInputLayout layout = new TextInputLayout(ctx);
            TextInputEditText edit = new TextInputEditText(layout.getContext());
            layout.addView(edit);
            UiKit.revalidate(layout, Validators::packageLpa);

            edit.setText("abc");
            assertNull("no error before the first submit", layout.getError());

            UiKit.check(layout, Validators.packageLpa("abc"));
            assertEquals("Enter the package in LPA", String.valueOf(layout.getError()));

            edit.setText("-1");
            assertEquals("Package must be between 0 and 200 LPA", String.valueOf(layout.getError()));

            edit.setText("12.5");
            assertNull("error clears once valid", layout.getError());
        });
    }
}
