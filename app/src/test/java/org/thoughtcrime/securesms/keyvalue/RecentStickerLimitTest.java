package org.thoughtcrime.securesms.keyvalue;

import android.app.Application;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE, sdk = 28)
public class RecentStickerLimitTest {
  @Test
  public void missingAndUnsupportedStoredLimitsUseTheExistingDefault() {
    Storage storage = new Storage();
    SettingsValues settings = settings(new KeyValueStore(storage));
    assertEquals(24, settings.getRecentStickerLimit());

    for (int invalid : new int[] {-1, 0, 1001, Integer.MAX_VALUE}) {
      storage.data.putInteger(SettingsValues.RECENT_STICKER_LIMIT, invalid);
      assertEquals(24, settings(new KeyValueStore(storage)).getRecentStickerLimit());
    }
  }

  @Test
  public void eachSelectionSurvivesReopeningAndIsIncludedInBackups() {
    Storage storage = new Storage();
    KeyValueStore store = new KeyValueStore(storage);
    SettingsValues settings = settings(store);
    assertTrue(settings.getKeysToIncludeInBackup().contains(SettingsValues.RECENT_STICKER_LIMIT));

    for (int limit : new int[] {1, 24, 37, 1000}) {
      settings.setRecentStickerLimit(limit);
      store.blockUntilAllWritesFinished();
      assertEquals(limit, settings(new KeyValueStore(storage)).getRecentStickerLimit());
    }
  }

  @Test
  public void unsupportedSelectionsDoNotReplaceTheSavedLimit() {
    SettingsValues settings = settings(new KeyValueStore(new Storage()));
    settings.setRecentStickerLimit(96);
    try {
      settings.setRecentStickerLimit(0);
      fail("An unsupported selection must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals(96, settings.getRecentStickerLimit());
    }
  }

  private SettingsValues settings(KeyValueStore store) {
    return new SettingsValues(store, RuntimeEnvironment.getApplication());
  }

  private static class Storage implements KeyValuePersistentStorage {
    private final KeyValueDataSet data = new KeyValueDataSet();

    @Override
    public synchronized void writeDataSet(@NonNull KeyValueDataSet updates, @NonNull Collection<String> removes) {
      data.putAll(updates);
      data.removeAll(removes);
    }

    @Override
    public synchronized @NonNull KeyValueDataSet getDataSet() {
      KeyValueDataSet copy = new KeyValueDataSet();
      copy.putAll(data);
      return copy;
    }
  }
}
