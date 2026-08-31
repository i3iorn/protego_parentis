package dev.schrammel.dementor_watch.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Who decides who is in the family.
 *
 * Parents approve; the child accepts what they are told. The one case that is not
 * that is the founding parent, who cannot be vouched for because there is nobody
 * to vouch for them.
 */
class EnrolmentTest {

    private val self = DeviceId("SELF-0000-0000")

    @Test
    fun `a parent phone approves`() {
        assertEquals(
            EnrolmentAuthority.APPROVES,
            Enrolment.authorityOf(SyncRole.PARENT, trustedParents = 0),
        )
        assertEquals(
            EnrolmentAuthority.APPROVES,
            Enrolment.authorityOf(SyncRole.PARENT, trustedParents = 3),
        )
    }

    /**
     * The heart of it. If the managed phone could approve parents, whoever holds
     * that phone — the person being limited — could add a parent device of their
     * own and answer their own requests.
     */
    @Test
    fun `the managed phone accepts rather than approves`() {
        assertEquals(
            EnrolmentAuthority.ACCEPTS,
            Enrolment.authorityOf(SyncRole.CHILD, trustedParents = 1),
        )
        assertFalse(Enrolment.mayApprove(SyncRole.CHILD, trustedParents = 1))
    }

    /** Somebody has to be first, and there is nobody to vouch for them. */
    @Test
    fun `the managed phone may enrol the founding parent`() {
        assertEquals(
            EnrolmentAuthority.BOOTSTRAPS,
            Enrolment.authorityOf(SyncRole.CHILD, trustedParents = 0),
        )
        assertTrue(Enrolment.mayApprove(SyncRole.CHILD, trustedParents = 0))
    }

    @Test
    fun `and never again once it has one`() {
        assertTrue(Enrolment.mayApprove(SyncRole.CHILD, trustedParents = 0))
        assertFalse(Enrolment.mayApprove(SyncRole.CHILD, trustedParents = 1))
    }

    // ---------- what a vouch may say ----------

    @Test
    fun `a consistent vouch for a parent is accepted`() {
        assertTrue(Enrolment.shouldAccept(vouch(), self))
    }

    /**
     * Nothing human looks at a vouch, so the fingerprint check a person would have
     * made has to happen here instead.
     */
    @Test
    fun `a vouch whose key does not match its id is refused`() {
        val impostor = vouch().copy(id = DeviceId("WRON-GWRO-NGWR"))

        assertFalse(impostor.isConsistent())
        assertFalse(Enrolment.shouldAccept(impostor, self))
    }

    @Test
    fun `a vouch with an unreadable key is refused`() {
        assertFalse(Enrolment.shouldAccept(vouch().copy(publicKey = "not base64 at all"), self))
    }

    /**
     * A second managed phone introduced remotely would receive every rule and
     * every position, on one compromised parent phone's say-so.
     */
    @Test
    fun `a vouch for a child device is refused`() {
        assertFalse(Enrolment.shouldAccept(vouch(role = SyncRole.CHILD), self))
    }

    @Test
    fun `a vouch for this phone itself is ignored`() {
        val keys = keyPair()
        val mine = Vouch(
            id = Fingerprints.of(keys.public.encoded),
            name = "Me",
            role = SyncRole.PARENT,
            publicKey = Fingerprints.encodeKey(keys.public.encoded),
        )

        assertFalse(Enrolment.shouldAccept(mine, selfId = mine.id))
    }

    /** Only a parent may vouch, whatever a message claims about itself. */
    @Test
    fun `vouching is a parent's message`() {
        assertEquals(
            SyncRole.PARENT,
            RosterRules.expectedSender(SyncMessageType.DEVICE_VOUCHED),
        )
    }

    // ---------- what a revoke may say ----------

    @Test
    fun `a revoke naming a parent is accepted`() {
        assertTrue(Enrolment.shouldAcceptRevoke(SyncRole.PARENT))
    }

    /**
     * Symmetric with [Enrolment.shouldAccept] never covering the managed phone:
     * un-enrolling it has to stay a local, PIN-gated action on that phone, not
     * something a remote parent's revoke can do to it or to everyone's trust in
     * it all at once.
     */
    @Test
    fun `a revoke naming the managed phone is refused`() {
        assertFalse(Enrolment.shouldAcceptRevoke(SyncRole.CHILD))
    }

    /** No roster entry for the named id means nothing is known to revoke. */
    @Test
    fun `a revoke naming an unknown device is refused`() {
        assertFalse(Enrolment.shouldAcceptRevoke(null))
    }

    /** Only a parent may revoke or disband, whatever a message claims about itself. */
    @Test
    fun `revoking and disbanding are a parent's message`() {
        assertEquals(
            SyncRole.PARENT,
            RosterRules.expectedSender(SyncMessageType.DEVICE_REVOKED),
        )
        assertEquals(
            SyncRole.PARENT,
            RosterRules.expectedSender(SyncMessageType.FAMILY_DISBANDED),
        )
    }

    // ---------- who may ask for or replace the rules ----------

    @Test
    fun `only a parent may request or replace the rules`() {
        assertEquals(SyncRole.PARENT, RosterRules.expectedSender(SyncMessageType.POLICY_REQUESTED))
        assertEquals(SyncRole.PARENT, RosterRules.expectedSender(SyncMessageType.POLICY_UPDATED))
    }

    @Test
    fun `only the managed phone may answer with its rules`() {
        assertEquals(SyncRole.CHILD, RosterRules.expectedSender(SyncMessageType.POLICY_SNAPSHOT))
    }

    @Test
    fun `only a parent may ask what apps are installed`() {
        assertEquals(
            SyncRole.PARENT,
            RosterRules.expectedSender(SyncMessageType.APP_INVENTORY_REQUESTED),
        )
    }

    @Test
    fun `only the managed phone may answer with its app list`() {
        assertEquals(SyncRole.CHILD, RosterRules.expectedSender(SyncMessageType.APP_INVENTORY))
    }

    private fun vouch(role: SyncRole = SyncRole.PARENT): Vouch {
        val keys = keyPair()
        return Vouch(
            id = Fingerprints.of(keys.public.encoded),
            name = "Dad's phone",
            role = role,
            publicKey = Fingerprints.encodeKey(keys.public.encoded),
        )
    }

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
}
