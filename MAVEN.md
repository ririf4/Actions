# Maven repository management

`maven-repository.yml` は既存のDiscord通知と同じく、`workflow_dispatch.inputs.payload` でbase64 JSONを受信します。Discord用のworkflow、payload形式、`run`
のエントリポイントは変更しません。

PCやCIからGradleの専用タスクで送る場合は、[Gradleプラグインの使い方](maven-gradle-plugin/README.md)を参照してください。`swiftStormPublish`
が一時ZIPの転送とActionsの完了確認まで行います。以下のGitHub artifact形式も引き続き利用できます。

## 初回設定

この変更を `ririf4/SendToDiscord` のデフォルトブランチに反映してください。GitHubのリポジトリSecretsに、作成済みのR2 S3 API認証情報を設定します。

| 名前                                                 | 内容                                                             |
|------------------------------------------------------|------------------------------------------------------------------|
| `R2_ACCESS_KEY_ID`                                   | `swiftstorm-maven` のObject Read & Writeキー                     |
| `R2_SECRET_ACCESS_KEY`                               | 上記キーのsecret                                                 |
| `MAVEN_SOURCE_TOKEN`（別リポジトリから取り込む場合） | 成果物のあるリポジトリに対するActions Read権限を持つGitHub token |

同じリポジトリの別runから取り込む場合は標準の `GITHUB_TOKEN`
が使えます。別リポジトリのartifactを取得するときは、対象にアクセスできるtokenが必要です。認証情報はJSONやartifactには入れません。既存tokenを利用できる場合はそれを設定してください。

R2 account IDは現在のaccountがworkflowに設定済みです。別accountではRepository Variable `R2_ACCOUNT_ID` を設定してください。bucketは `swiftstorm-maven` 固定です。

## payload

```json
{
  "operation": "publish",
  "repository": "releases",
  "group_id": "dev.swiftstorm",
  "artifact_id": "demo",
  "version": "1.0.0",
  "source_repository": "ririf4/demo",
  "source_run_id": "123456789",
  "source_artifact": "maven-publication",
  "dry_run": true
}
```

`source_run_id` は文字列です。`dry_run` は省略時も `true` です。実際に変更する場合は `false` を指定してください。dry
runもR2の現状と取得artifactを検証しますが、公開領域の書き込み・削除は行いません。未知のJSONフィールドや不正な型は拒否します。

Gradleプラグインはsource情報の代わりに、`source_bundle_key: "staging/requests/<UUID>.zip"` と `source_bundle_sha256: "<64文字の小文字hex>"`
を送ります。artifact形式とbundle形式は混在できません。bundleは指定coordinateのversionディレクトリだけを含むZIPです。成功した処理ではdry runを含めZIPを削除し、失敗時は残します。

| operation | 挙動                                                                                                |
|-----------|-----------------------------------------------------------------------------------------------------|
| `publish` | 指定バージョンを公開。既存releaseは同一内容のみ再実行可能。ファイルの追加・差し替えは禁止           |
| `update`  | `snapshots` の `*-SNAPSHOT` のみ。新しいtimestamp/build番号の成果物を公開してversion metadataを更新 |
| `delete`  | 指定バージョン配下の全ファイルを削除し、artifact metadataからそのバージョンを除去                   |

releaseの更新は新しいversionをpublishしてください。`releases` には通常version、`snapshots` には末尾 `-SNAPSHOT` のversionだけを受け付けます。

古いSNAPSHOTのtimestamp/build番号への巻き戻しと、同じtimestamp付きファイルの差し替えも拒否します。POMのgroupId/artifactId/versionがpayloadと一致することを検証するため、座標が解決済みのpublish用POMが必要です。groupId/versionのparentからの継承は対応しています。

deleteではsource情報は不要です。対象確認文字列が必須です。

```json
{
  "operation": "delete",
  "repository": "releases",
  "group_id": "dev.swiftstorm",
  "artifact_id": "demo",
  "version": "1.0.0",
  "confirm_delete": "dev.swiftstorm:demo:1.0.0",
  "dry_run": true
}
```

## 成果物を作る側

Gradleの `maven-publish` で、ローカルのMaven形式ディレクトリにpublishします。`publishToMavenLocal` はSNAPSHOT用のremote metadataを生成しないため使いません。

```kotlin
publishing {
    repositories {
        maven {
            name = "ActionsStaging"
            url = layout.buildDirectory.dir("maven-repository").get().asFile.toURI()
        }
    }
}
```

成果物側のworkflowでビルド後に追加します。既存のディレクトリがある場合は、そのディレクトリをartifactにできます。

```yaml
- name: Stage Maven publication
  run: ./gradlew publishAllPublicationsToActionsStagingRepository
- uses: actions/upload-artifact@v4
  with:
    name: maven-publication
    path: build/maven-repository/
    if-no-files-found: error
```

artifactのルートにはgroupIdから始まる階層が必要です。例えば `dev/swiftstorm/demo/1.0.0/demo-1.0.0.pom` と `.jar` が入ります。SNAPSHOTの場合はversionディレクトリ内の
`maven-metadata.xml` と、そこで参照するtimestamp付きファイルが必要です。通常のGradle local file repositoryへのpublishで生成できます。

1 requestで1
coordinateを処理します。artifact内に他のpublicationがあっても指定coordinate以外は取り込みません。POM単体のpublicationも可能です。checksumは入力をコピーせずMD5/SHA1/SHA256/SHA512を再計算します。symlink、階層を抜けるpath、version内のサブディレクトリを拒否します。上限は1ファイル128
MiB、1 publication 512 MiBです。

成果物のuploadが成功した後に、呼び出し元の既存dispatch用認証を使って以下のように起動できます。`GH_TOKEN` には受信リポジトリへのActions Write権限が必要です。別リポジトリからの通常の
`GITHUB_TOKEN` はこの権限を持ちません。

```bash
encoded=$(jq -nc \
  --arg repo "$GITHUB_REPOSITORY" \
  --arg run "$GITHUB_RUN_ID" \
  '{operation:"publish",repository:"releases",group_id:"dev.swiftstorm",artifact_id:"demo",version:"1.0.0",source_repository:$repo,source_run_id:$run,source_artifact:"maven-publication",dry_run:true}' \
  | base64 -w0)
jq -nc --arg payload "$encoded" '{payload:$payload}' \
  | gh workflow run maven-repository.yml --repo ririf4/SendToDiscord --json
```

初回はdry runで取り込み予定を確認し、その後 `dry_run:false` に変更してください。workflowの起動受付は公開完了を意味しません。受信側のrunの完了状態を確認してください。

## metadataと競合

artifact-level `maven-metadata.xml` はR2の既存バージョン一覧にmergeし、最新のGradle出力で丸ごと置換しません。既存の追加要素も保持します。通常形式の `latest` /
`release`
は最後に追加したバージョンです。削除時、該当ポインタは残っている一覧の最後の適切なversionに戻します。最後のversionを削除した後は空のmetadataを残し、checksumを更新します。

### GenCal-SemVer

`[Generation][YY].Minor.Patch.rRevision-PreRelease+Meta` を自動認識します。例: `126.3.5.r105-beta.2+env26.1.2.sha67b5b073`。
Generationは1以上、YYは末尾2桁、Minorは1以上、Patch/Revisionは0以上です。数値は先頭ゼロを許可せず、YYだけは2桁固定です。プレリリース識別子はSemVer方式（ASCII英数字とハイフン、ドット区切り）で、数値識別子の先頭ゼロは拒否します。

比較順はGeneration → YY → Minor → Patch → Revision → プレリリースです。数字は数値比較し、プレリリースはSemVer方式で比較します。同じ数値部分なら正式版がテスト版より上です。
`+` 以降は比較から除外します。
GenCalバージョンが存在する場合、`versions` 内のGenCal部分を昇順に並べ、`latest` は最高のGenCal、`release`
は最高の正式GenCalを選びます。古い版・betaを後から公開してもこれらのポインタを巻き戻しません。正式GenCalがない場合、既存の通常形式のreleaseを使い、通常形式もなければ
`release` を省略します。削除後も残るバージョンから再判定します。

比較が等しいbuild metadata違いは、既存のポインタを維持します。ただしMaven座標・R2キー・ファイル名はフル文字列を保持し、別の公開物として扱います。メタデータ違いを同じ保存先に統合せず、releaseの上書きも許可しません。
通常形式のバージョンは引き続き受け付け、GenCalと混在する場合も削除せず元の掲載順を保持します。GenCal形式に合わない文字列は従来の通常形式として扱います。`-SNAPSHOT`
は従来のsnapshot処理を継続し、GenCal比較には含めません。beta/rcだけなら `releases` にpublishします。

共通ライブラリの `GenCalVersion.parse(value)` で解析・比較でき、`publicVersion` は `126.3.5.r105…` から `2026.3.5`
を返します。世紀は元データから判別できないため2000–2099を前提にしています。年・リビジョンの自動採番、外部環境の互換性検証、public versionの別座標への同時公開は行いません。

この詳細表記は標準SemVerそのものではありません。Mavenのversion
rangeやGradleの動的依存解決はそれぞれの比較規則を使うため、今回の規則を適用できるのは受信側のmetadata更新と共通ライブラリの比較です。利用側で確実に同じ成果物を取得するにはフルバージョンを固定指定してください。

全リクエストは受信側リポジトリで直列に実行し、最大100件の待機queueを利用します。全書き込みでETagによる条件付きPUTを使い、処理中の変更を検出したら失敗させます。別の直接書き込み経路との同時実行は避けてください。

R2には複数objectをまとめるトランザクションがありません。artifact → SNAPSHOT version metadata → artifact metadataとchecksumの順に書き込みます。deleteはartifact
metadataから対象を外してchecksumを更新してから対象ディレクトリを削除します。途中失敗時は同じpayloadと同じartifactで再実行して完了させてください。転送失敗やmetadata競合時に公開途中のファイルが残る可能性があります。自動rollbackはしません。

公開WorkerへのPUT/DELETEを追加する必要はありません。既存のGET/HEADとdirectory listingはそのまま使います。

## ローカル確認

Java 21で以下を実行します。

```powershell
.\gradlew.bat test
.\gradlew.bat manageMaven --args="payload.json --validate"
.\gradlew.bat manageMaven --args="payload.json incoming"
```

最後のコマンドにはR2環境変数が必要です。テストはメモリstoreとローカルHTTP serverで行い、本番データには触れません。

仕様の参照: [GitHub artifactの別runからの取得](https://github.com/actions/download-artifact/tree/v4)、[GitHub workflow concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)、[R2 S3対応API](https://developers.cloudflare.com/r2/api/s3/api/)、[Maven repository metadata](https://maven.apache.org/ref/3.9.11/maven-repository-metadata/repository-metadata.html)。
