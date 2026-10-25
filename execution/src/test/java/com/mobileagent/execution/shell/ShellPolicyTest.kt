package com.mobileagent.execution.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShellCommandPolicyTest {

    @Test
    fun `catastrophic commands are blocked`() {
        assertNotNull(ShellCommandPolicy.blockedReason("rm -rf /"))
        assertNotNull(ShellCommandPolicy.blockedReason("rm -rf /system"))
        assertNotNull(ShellCommandPolicy.blockedReason("mkfs.ext4 /dev/block/sda1"))
        assertNotNull(ShellCommandPolicy.blockedReason("dd if=/dev/zero of=/dev/block/by-name/userdata"))
    }

    @Test
    fun `normal commands are allowed`() {
        assertNull(ShellCommandPolicy.blockedReason("getprop ro.build.version.release"))
        assertNull(ShellCommandPolicy.blockedReason("ls -la /sdcard/Download"))
        assertNull(ShellCommandPolicy.blockedReason("pm list packages -3"))
        assertNull(ShellCommandPolicy.blockedReason("rm -rf workspace/cache"))
    }

    @Test
    fun `empty command is rejected`() {
        assertNotNull(ShellCommandPolicy.blockedReason("   "))
    }
}

class UrlPolicyTest {

    @Test
    fun `public https urls are accepted`() {
        assertNull(com.mobileagent.execution.tools.UrlPolicy.rejectionReason("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed"))
        assertNull(com.mobileagent.execution.tools.UrlPolicy.rejectionReason("http://example.com/data.json"))
    }

    @Test
    fun `loopback and private ranges are rejected`() {
        assertNotNull(com.mobileagent.execution.tools.UrlPolicy.rejectionReason("http://localhost:8080/"))
        assertNotNull(com.mobileagent.execution.tools.UrlPolicy.rejectionReason("http://127.0.0.1/"))
        assertNotNull(com.mobileagent.execution.tools.UrlPolicy.rejectionReason("http://192.168.1.1/"))
        assertNotNull(com.mobileagent.execution.tools.UrlPolicy.rejectionReason("http://10.0.0.5/"))
        assertNotNull(com.mobileagent.execution.tools.UrlPolicy.rejectionReason("http://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun `non http schemes are rejected`() {
        assertNotNull(com.mobileagent.execution.tools.UrlPolicy.rejectionReason("file:///etc/passwd"))
        assertNotNull(com.mobileagent.execution.tools.UrlPolicy.rejectionReason("ftp://example.com"))
    }

    @Test
    fun `missing host is rejected`() {
        assertFalse(
            com.mobileagent.execution.tools.UrlPolicy.rejectionReason("not-a-url") == null,
        )
    }
}
