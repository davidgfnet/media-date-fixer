# Media Date Fixer: plain SDK build (aapt2 + javac + d8), no Gradle.
#   make ANDROID_SDK=$HOME/android-sdk          -> mediadatefixer.apk (signed with debug.keystore)
#   make test                                   -> JVM unit tests (no SDK needed)
#   make ANDROID_SDK=$HOME/android-sdk run      -> adb install + launch

# --- Config (override on the command line or via env) ---
BUILD_TOOLS_VERSION ?= 36.0.0
PLATFORM_VERSION    ?= android-36
MIN_API             ?= 30
JAVAC_FLAGS         ?= --release 17
KEYSTORE            ?= debug.keystore
ANDROID_SDK         ?= $(ANDROID_HOME)
export KS_PASS      ?= android

BT        = $(ANDROID_SDK)/build-tools/$(BUILD_TOOLS_VERSION)
PLATFORM  = $(ANDROID_SDK)/platforms/$(PLATFORM_VERSION)/android.jar
AAPT2     = $(BT)/aapt2
D8        = $(BT)/d8
ZIPALIGN  = $(BT)/zipalign
APKSIGNER = $(BT)/apksigner

PKG      := net.davidgf.mediadatefixer
SRC      := $(shell find src -name '*.java')
RES      := $(shell find res -type f)
CORE_SRC := $(shell find src -path '*/core/*.java')
TEST_SRC := $(shell find test -name '*.java')
TESTS    := $(patsubst test/%.java,%,$(shell find test -path '*/core/*Test.java'))
BUILD    := build
APK      := mediadatefixer.apk

all: $(APK)

sdk-check:
	@test -n "$(ANDROID_SDK)" || { echo 'Set ANDROID_SDK (or ANDROID_HOME), e.g. make ANDROID_SDK=$$HOME/android-sdk'; exit 1; }

# Resources -> resource APK + R.java
$(BUILD)/res.zip: $(RES) | sdk-check
	@mkdir -p $(BUILD)
	$(AAPT2) compile --dir res -o $@

$(BUILD)/base.apk: AndroidManifest.xml $(BUILD)/res.zip
	rm -rf $(BUILD)/gen && mkdir -p $(BUILD)/gen
	$(AAPT2) link -o $@ -I $(PLATFORM) --manifest AndroidManifest.xml --java $(BUILD)/gen $(BUILD)/res.zip

# Java -> classes.dex
$(BUILD)/classes.dex: $(SRC) $(BUILD)/base.apk
	rm -rf $(BUILD)/classes && mkdir -p $(BUILD)/classes
	javac $(JAVAC_FLAGS) -encoding UTF-8 -classpath $(PLATFORM) -d $(BUILD)/classes \
	  $(SRC) $(shell find $(BUILD)/gen -name '*.java' 2>/dev/null)
	$(D8) --release --min-api $(MIN_API) --lib $(PLATFORM) --output $(BUILD) \
	  $$(find $(BUILD)/classes -name '*.class' | LC_ALL=C sort)

# Unsigned APK: the resource APK plus classes.dex
$(BUILD)/unsigned.apk: $(BUILD)/base.apk $(BUILD)/classes.dex
	cp $(BUILD)/base.apk $(BUILD)/packed.apk
	zip -qj $(BUILD)/packed.apk $(BUILD)/classes.dex
	$(ZIPALIGN) -f 4 $(BUILD)/packed.apk $@

unsigned: $(BUILD)/unsigned.apk

debug.keystore:
	keytool -genkeypair -keystore $@ -alias androiddebugkey \
	  -storepass android -keypass android -keyalg RSA -keysize 2048 \
	  -validity 10000 -dname "CN=Android Debug,O=Android,C=US"

$(APK): $(BUILD)/unsigned.apk $(KEYSTORE)
	$(APKSIGNER) sign --ks $(KEYSTORE) --ks-pass env:KS_PASS --out $@ $(BUILD)/unsigned.apk
	@ls -l $@

# Core unit tests on the plain JVM (the core has no android.* imports)
test:
	rm -rf $(BUILD)/test && mkdir -p $(BUILD)/test
	javac $(JAVAC_FLAGS) -encoding UTF-8 -d $(BUILD)/test $(CORE_SRC) $(TEST_SRC)
	java -cp $(BUILD)/test $(PKG).testing.Testing $(subst /,.,$(TESTS))

install: $(APK)
	$(ANDROID_SDK)/platform-tools/adb install -r $(APK)

run: install
	$(ANDROID_SDK)/platform-tools/adb shell am start -n $(PKG)/.HomeActivity

clean:
	rm -rf $(BUILD) $(APK) $(APK).idsig

.PHONY: all sdk-check unsigned test install run clean
