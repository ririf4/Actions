# バージョン比較とMaven metadata

受信側のpublish/delete処理は、artifact-level `maven-metadata.xml` のバージョン一覧を保持して最新ポインタを再計算します。

## SemVer 2.0

`MAJOR.MINOR.PATCH[-PreRelease][+BuildMetadata]` を自動認識します。追加設定は不要です。

```kotlin
version = "4.2.0"
// version = "4.3.0-beta.2+sha67b5b073"
```

Major/Minor/Patchは数値で比較し、同じ数値部分では正式版をプレリリースより上にします。プレリリースのドット区切り数字も数値比較します。`+`
以降は比較から除外しますが、公開座標・保存キー・ファイル名には残します。数値の先頭ゼロ、空の識別子、非ASCII文字はSemVerとして認識しません。

- `latest`: プレリリースも含む最高バージョン。
- `release`: 正式版だけの最高バージョン。正式版がなければ省略。
- `versions`: SemVer部分を昇順に掲載。古い版を後から公開してもポインタは巻き戻しません。
- 削除: 残ったバージョンから再計算。すべて削除した場合はポインタを省略します。
- 同じ優先度のbuild metadata違い: 別の公開座標として保持し、既存ポインタが候補に残っていればその値を維持します。

例: `4.2.0` のあとに `4.3.0-beta.2` を公開すると、`latest=4.3.0-beta.2`、`release=4.2.0` です。`4.3.0` を公開すれば両方が `4.3.0` になります。

`SemVerVersion.parse(value)` で共通ライブラリから解析・比較することもできます。

## GenCalとその他の形式

`126.3.5.r105-beta.2+env26.1.2.sha67b5b073` のようなGenCal形式は従来どおり世代・年・Minor・Patch・Revision・プレリリース順で比較します。
同じartifactに複数形式が混在する場合、既存のGenCal優先を保持し、`latest` はGenCal → SemVer → その他の順で選びます。`release` は正式GenCal → 正式SemVer →
その他の非SNAPSHOTの順です。形式間の数値を直接比較しません。

`1.0` など認識できない形式は引き続き受け付け、その形式しかなければ従来の「最後に追加した版」を使います。一覧から削除せず、掲載順も保持します。`-SNAPSHOT`
は従来のtimestamp/build番号による更新を継続し、今回の比較対象には含めません。

Maven/Gradleの依存解決アルゴリズム自体は変更しません。特にMavenはbuild metadataを無視するSemVer 2.0比較ではありません。今回の規則を適用する範囲は受信側のmetadata更新と共通ライブラリです。

仕様: [SemVer 2.0](https://semver.org/)、[Mavenのバージョン比較](https://maven.apache.org/pom.html#Version_Order_Specification)。
