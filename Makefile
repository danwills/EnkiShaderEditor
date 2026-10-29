PACKAGE = enk.danwills.android.enkishadereditor
# Ultra Rest Area, the second app - see docs/ultra-rest-area.md.
URA_PACKAGE = enk.danwills.ultrarestarea

all: debug install start

debug:
	./gradlew assembleEnkiDebug

release: lint
	./gradlew assembleEnkiRelease

bundle: lint
	./gradlew bundleEnkiRelease

lint:
	./gradlew lintEnkiDebug

infer: clean
	infer -- ./gradlew assembleEnkiDebug

install:
	adb $(TARGET) install -r app/build/outputs/apk/enki/debug/app-enki-debug.apk

start:
	adb $(TARGET) shell 'am start -n \
		$(PACKAGE).debug/$(PACKAGE).activity.SplashActivity'

ura-debug:
	./gradlew assembleUltrarestareaDebug

ura-release:
	./gradlew assembleUltrarestareaRelease

ura-install:
	adb $(TARGET) install -r \
		app/build/outputs/apk/ultrarestarea/debug/app-ultrarestarea-debug.apk

ura-start:
	adb $(TARGET) shell 'am start -n \
		$(URA_PACKAGE).debug/$(PACKAGE).restarea.RestAreaActivity'

uninstall:
	adb $(TARGET) uninstall $(PACKAGE).debug

meminfo:
	adb shell dumpsys meminfo $(PACKAGE).debug

glxinfo:
	adb shell dumpsys gfxinfo $(PACKAGE).debug

images:
	svg/update.sh

avocado:
	avocado $(shell fgrep -rl '<vector' app/src/main/res)

clean:
	./gradlew clean
