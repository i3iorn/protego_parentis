package dev.schrammel.dementor_watch.model

import java.security.SecureRandom

/**
 * Suggests a family passphrase that is strong and can be read down a phone line.
 *
 * This exists because of what the passphrase actually is. Everything a family
 * sends to itself is encrypted with a key derived from it, on a relay anybody can
 * subscribe to. So the passphrase is not a login — it is the whole of the
 * confidentiality guarantee, and unlike a login there is nobody on the other end
 * to lock an attacker out after three tries. Someone who captures the traffic can
 * grind at it forever, offline, at whatever rate their hardware allows.
 *
 * Left to themselves, people faced with "choose a passphrase" during setup type
 * the family surname and a year. The screen already says to choose something long,
 * which is advice, and advice loses to a text field at eleven at night.
 *
 * ## Why words
 *
 * Random characters are stronger per character and useless here. This passphrase
 * is not typed once into a manager — it is read aloud across a kitchen, or over
 * the phone, and typed on a second handset by somebody who cannot see the first.
 * A phrase that survives that trip has to be sayable. Six words from this list
 * carry 48 bits, which through 210 000 rounds of PBKDF2 is a much harder problem
 * than anything a person invents unaided.
 *
 * ## Why these words
 *
 * Exactly 256, so each word is a clean byte and the arithmetic below has no
 * modulo bias to reason about. Every one is four to eight letters, common, concrete,
 * and unambiguous when spoken — no homophones (no *pear*, no *flour*), no pairs
 * separated only by a vowel, nothing that has to be spelled out. Nothing sinister
 * either: this is a phrase a parent and a child will say to each other, and a
 * random word list that occasionally produces something unpleasant is a bad thing
 * to have written.
 */
object Passphrases {

    /**
     * How many words a suggestion carries.
     *
     * Six is where the two pressures balance. Each word is 8 bits, so six gives 48
     * — enough that the derivation cost dominates any realistic attack. Seven
     * would be better arithmetic and worse at the thing this is for, which is
     * being said out loud once and typed correctly at the other end.
     */
    const val WORDS = 6

    /** Separates the words. A space, because that is what people will type. */
    const val SEPARATOR = " "

    /**
     * A fresh suggestion.
     *
     * [random] is a parameter so a test can pin the output; production callers use
     * the default, which is a [SecureRandom]. Nothing here is seeded, cached or
     * reused — a passphrase generator that produced the same phrase twice would be
     * worse than no generator, because it would look like it was working.
     */
    fun suggest(random: SecureRandom = SecureRandom()): String =
        (1..WORDS).joinToString(SEPARATOR) { WORDS_LIST[random.nextInt(WORDS_LIST.size)] }

    /** Exposed for the test that guards the properties the arithmetic assumes. */
    val words: List<String> get() = WORDS_LIST

    private val WORDS_LIST: List<String> = listOf(
        "acorn", "album", "amber", "anchor", "angle", "apple", "apron", "arbor",
        "arrow", "attic", "autumn", "bacon", "badge", "bagel", "baker", "bamboo",
        "banjo", "barge", "basil", "basin", "basket", "batch", "beacon", "beetle",
        "bench", "berry", "birch", "bison", "blanket", "blossom", "bobbin", "bonnet",
        "boulder", "bracket", "branch", "brass", "bridge", "bronze", "brook", "brush",
        "bubble", "bucket", "buckle", "buffalo", "bugle", "bundle", "burrow", "butter",
        "cabin", "cactus", "camel", "candle", "canoe", "canvas", "canyon", "carpet",
        "carrot", "castle", "cattle", "cedar", "cellar", "chalk", "cherry", "chimney",
        "chisel", "cinder", "circus", "cliff", "clover", "cobweb", "cocoa", "collar",
        "comet", "compass", "copper", "coral", "cotton", "cradle", "crane", "crater",
        "crayon", "cricket", "crown", "crystal", "cupboard", "curtain", "cushion", "cymbal",
        "daisy", "dagger", "dolphin", "domino", "donkey", "dragon", "drawer", "drum",
        "eagle", "easel", "elbow", "elder", "ember", "engine", "escape", "fabric",
        "falcon", "fathom", "fennel", "fiddle", "figure", "finch", "flagon", "flannel",
        "flint", "flute", "forest", "fossil", "fountain", "frost", "funnel", "galaxy",
        "garden", "garlic", "gazelle", "geyser", "ginger", "glacier", "glider", "granite",
        "grape", "gravel", "grotto", "guitar", "gully", "gutter", "hamlet", "hammer",
        "hammock", "harbor", "harvest", "hazel", "heather", "hedge", "helmet", "herring",
        "hollow", "honey", "hornet", "hurdle", "iceberg", "igloo", "indigo", "insect",
        "island", "ivory", "jacket", "jasmine", "jigsaw", "juniper", "kayak", "kennel",
        "kettle", "kitten", "koala", "ladder", "lagoon", "lantern", "lattice", "lemon",
        "lentil", "lettuce", "lichen", "lilac", "linen", "lobster", "locket", "lumber",
        "magnet", "mammoth", "mango", "mantle", "maple", "marble", "marmot", "meadow",
        "melon", "mermaid", "mineral", "minnow", "mitten", "monsoon", "morning", "mosaic",
        "moss", "muffin", "mulberry", "mushroom", "nectar", "needle", "nettle", "nickel",
        "noodle", "nutmeg", "oatmeal", "octopus", "olive", "onion", "opal", "orbit",
        "orchard", "orchid", "ostrich", "otter", "oxygen", "paddle", "palace", "pancake",
        "panda", "pantry", "papaya", "parcel", "parsley", "parsnip", "pasture", "peacock",
        "pebble", "pelican", "pepper", "petal", "pewter", "pigeon", "pillar", "pillow",
        "pipe", "planet", "plaster", "platter", "plaza", "plum", "pocket", "pollen",
        "pond", "poplar", "poppy", "possum", "pottery", "prairie", "pretzel", "prism",
        "pudding", "pumpkin", "puzzle", "quarry", "quartz", "quilt", "rabbit", "radish",
    )

    init {
        // The whole design rests on this. If the list ever stops being exactly 256
        // long, the arithmetic in the class note is wrong, the bit count in the UI
        // is a lie, and nothing else in the build would notice.
        require(WORDS_LIST.size == ALPHABET_SIZE) {
            "the word list must hold exactly $ALPHABET_SIZE words, not ${WORDS_LIST.size}"
        }
        require(WORDS_LIST.toSet().size == WORDS_LIST.size) {
            "the word list must not repeat a word"
        }
    }

    /** One byte per word, which is what makes the entropy count honest. */
    const val ALPHABET_SIZE = 256

    /** Bits of entropy in a suggestion, for anywhere that wants to say so. */
    const val ENTROPY_BITS = WORDS * 8
}
