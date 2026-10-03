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
      # the specification's peer runner, ktlint for the lint, and no git hooks.
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
            ];
            JAVA_HOME = t.jdk.home;
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
            ktlint --relative "engine/**/*.kt" "*.kts" "engine/*.kts"
            touch $out
          '';

          # The local gate and the two Node drivers parse, and the gate passes shellcheck.
          scripts =
            pkgs.runCommand "jetbrains-client-scripts"
              {
                nativeBuildInputs = [
                  t.shellcheck
                  t.node
                ];
              }
              ''
                shellcheck ${self}/scripts/ci-local.sh ${self}/scripts/run-peer-vectors.sh
                node --check ${self}/engine/src/test/node/yjs-driver.mjs
                node --check ${self}/engine/src/test/node/ts-peer.mjs
                touch $out
              '';

          devshell = self.devShells.${pkgs.system}.default;
        }
      );
    };
}
