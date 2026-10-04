{
  description = "Selvage for JetBrains IDEs: the dev shell and the server-free checks";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];

      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # The tools the build and its checks run with. Gradle itself comes from the wrapper
      # (`gradle/wrapper/gradle-wrapper.properties`); the `gradle` here only regenerates it.
      tools = pkgs: {
        jdk = pkgs.jdk21;
        node = pkgs.nodejs_22;
        inherit (pkgs) gradle ktlint shellcheck;
        # The specification's peer runner verifies and seals frames with these.
        python = pkgs.python3.withPackages (ps: [
          ps.jsonschema
          ps.referencing
          ps.cryptography
        ]);
      };
    in
    {
      # JDK 21 for Gradle and the engine, Node for the differential test against yjs, Python for
      # the specification's peer runner, ktlint and actionlint for the lint, and no git hooks.
      devShells = forAllSystems (
        pkgs:
        let
          t = tools pkgs;
        in
        {
          default = pkgs.mkShell {
            packages = [
              t.jdk
              t.node
              t.gradle
              t.ktlint
              t.shellcheck
              t.python
              # The display each instance of the two-IDE end-to-end test draws on.
              pkgs.xvfb
              pkgs.lychee
              # The workflows' lint, which runs the `run:` blocks through the shellcheck above.
              pkgs.actionlint
            ];
            JAVA_HOME = t.jdk.home;
            # The test IDE's runtime loads these when an editor lays out text, headless or not.
            SELVAGE_TEST_LIBRARY_PATH = pkgs.lib.makeLibraryPath [
              pkgs.freetype
              pkgs.fontconfig
              pkgs.zlib
            ];
            # What a real IDE window needs besides those, for the two-IDE end-to-end test.
            SELVAGE_E2E_LIBRARY_PATH = pkgs.lib.makeLibraryPath [
              pkgs.libx11
              pkgs.libxext
              pkgs.libxrender
              pkgs.libxtst
              pkgs.libxi
              pkgs.libxrandr
              pkgs.libxcursor
              pkgs.libxfixes
              pkgs.libxxf86vm
              pkgs.freetype
              pkgs.fontconfig
              pkgs.zlib
              pkgs.libGL
            ];
            SELVAGE_NODE = "${t.node}/bin/node";
            # Temporary files stay in the checkout: the JVM ignores TMPDIR, so it is told as well.
            shellHook = ''
              export TMPDIR="$(git rev-parse --show-toplevel 2>/dev/null || pwd)/.tmp"
              mkdir -p "$TMPDIR"
              export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR -XX:-UsePerfData"
            '';
          };
        }
      );

      # What a sandboxed build can check without Maven Central: the Gradle suite needs its
      # dependencies from the network, so it runs from `scripts/ci-local.sh checks` instead.
      checks = forAllSystems (
        pkgs:
        let
          t = tools pkgs;
        in
        {
          # ktlint over the Kotlin sources, with the rules `.editorconfig` sets.
          ktlint = pkgs.runCommand "jetbrains-client-ktlint" { nativeBuildInputs = [ t.ktlint ]; } ''
            cp -r ${self} work
            chmod -R u+w work
            cd work
            ktlint --relative "engine/**/*.kt" "plugin/**/*.kt" "*.kts" "engine/*.kts" "plugin/*.kts"
            touch $out
          '';

          # The local gate and the two Node drivers parse, and the gate passes shellcheck.
          scripts =
            pkgs.runCommand "jetbrains-client-scripts"
              {
                nativeBuildInputs = [
                  t.shellcheck
                  t.node
                  t.python
                ];
              }
              ''
                shellcheck ${self}/scripts/ci-local.sh ${self}/scripts/run-peer-vectors.sh \
                  ${self}/scripts/e2e/run-two-instance.sh ${self}/scripts/bump-version.sh \
                  ${self}/scripts/test-bump-version.sh
                python3 -c 'import ast, sys; ast.parse(open(sys.argv[1]).read())' ${self}/scripts/e2e/two_instance.py
                node --check ${self}/engine/src/test/node/yjs-driver.mjs
                node --check ${self}/engine/src/test/node/ts-peer.mjs
                node --check ${self}/plugin/src/test/node/bridge-driver.mjs
                touch $out
              '';

          # The guard around what a workflow's `dry_run` input promises
          # (`scripts/check_dry_run_gating.py`). `release.yml` declares the input, its plan step
          # carries `inputs.dry_run == true` and every step after it `inputs.dry_run != true`,
          # which is the whole of what keeps a rehearsal from cutting a release; a step added
          # below the plan with no condition performs the bump the plan said it would not.
          # `actionlint` lints that file clean, because the defect is the condition a step does
          # *not* carry. Python and PyYAML only, and the sandbox's own `TMPDIR` takes the suite's
          # scratch.
          dry-run-gating =
            pkgs.runCommand "jetbrains-client-dry-run-gating"
              {
                nativeBuildInputs = [ (pkgs.python3.withPackages (ps: [ ps.pyyaml ])) ];
              }
              ''
                python3 -B ${self}/scripts/test_check_dry_run_gating.py
                python3 -B ${self}/scripts/check_dry_run_gating.py ${self}/.github/workflows
                touch $out
              '';

          devshell = self.devShells.${pkgs.system}.default;
        }
      );
    };
}
