package com.richard.imeiprivacy;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.Collections;

import static org.junit.Assert.*;

/** Optional local integration: no APK, ROM or package version is used by production discovery. */
public class AdaptiveDiscoveryApkTest {
    @Test public void sameRulesDiscoverAllSuppliedApks() throws Exception {
        String paths = System.getProperty("privacy.compat.apks", "");
        Assume.assumeFalse("Pass -PcompatibilityApks using the platform path separator", paths.isEmpty());
        for (String path : paths.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            long started = System.nanoTime();
            DexHookPlan plan = DexSemanticDiscovery.scan(Collections.singletonList(new File(path)));
            System.out.println(new File(path).getName() + ": dex=" + plan.dexFiles + " classes=" + plan.classes
                    + " info=" + plan.infos.size() + " fid=" + plan.credentials.size()
                    + " cache=" + plan.fidCaches.size() + " cloud=" + plan.cloudProviders.size()
                    + " requests=" + plan.requests.size() + " transport=" + plan.transports.size()
                    + " rejected=" + plan.rejected + " ms=" + (System.nanoTime() - started) / 1_000_000);
            for (DexHookPlan.Info info : plan.infos) {
                System.out.println("info=" + info.owner + " fields=" + info.fields + " readers=" + info.readers.size());
            }
            assertTrue("device info missing: " + path, plan.infos.size() > 0);
            assertTrue("FID getters missing: " + path, plan.credentials.size() >= 2);
            assertTrue("CloudId provider missing: " + path, plan.cloudProviders.size() > 0);
            assertTrue("request entry missing: " + path, plan.requests.size() > 0);
            assertTrue("HTTP boundary missing: " + path, plan.transports.size() > 0);
        }
    }
}
