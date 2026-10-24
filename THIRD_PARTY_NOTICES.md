# Third-Party Notices

agents-room 자체의 라이선스는 [LICENSE](LICENSE)에 있다.
이 문서는 저장소와 배포물에 들어가는 제3자 코드와 에셋을 고지한다. 항목마다 대상 파일, 출처, 라이선스, 저작권 표기, 비고를 적는다.

## Pixel Agents

- 대상: 포크로 이어받은 코드와 에셋. 아래 항목에 따로 적은 것은 그 항목을 따른다.
- 출처: [pixel-agents-hq/pixel-agents](https://github.com/pixel-agents-hq/pixel-agents), 포크 기준 `d1e007a` ([docs/upstream.md](docs/upstream.md#포크-기준))
- 라이선스: MIT
- 저작권 표기: Copyright (c) 2026 Pablo De Lucca
- 비고: 업스트림 LICENSE 전문은 아래와 같다.

```text
MIT License

Copyright (c) 2026 Pablo De Lucca

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 캐릭터 스프라이트

- 대상: `webview-ui/public/assets/characters/char_0.png`–`char_5.png`. 업스트림 커밋 `5a97a00`에서 팔레트별로 미리 칠해 추가했고, 이후 `47b2571`, `0412026`, `10ffeff`에서 수정했다.
- 출처: JIK-A-4, [Metro City 무료 top-down 캐릭터 팩](https://jik-a-4.itch.io/metrocity-free-topdown-character-pack)
- 라이선스: [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/). 근거는 itch.io 페이지의 표기다 (2026-10-20 확인).
- 저작권 표기: 없음 (CC0). 원작자는 JIK-A-4다.
- 비고: 저장소에는 이 팩의 라이선스 파일이 없다. 크레딧은 필수가 아니지만 권장된다.

## 폰트

- 대상: `webview-ui/public/fonts/FSPixelSansUnicode-Regular.ttf` ("FS Pixel Sans Unicode Regular", Version 1.0)
- 출처: NZWStudios2024, [FontStruct](https://fontstruct.com/fontstructions/show/2606508/fs-pixel-sans-unicode-regular). FontStruct로 만들었고 "FS Pixel Classic Regular"를 확장했다.
- 라이선스: SIL Open Font License. 폰트 메타데이터에는 버전과 URL이 없다 (nameID 13 = "Open Font License"). 아래 전문은 OFL 1.1이다 (버전은 추정).
- 저작권 표기: `Copyright NZWStudios2024 2025` 다음 줄에 `“FS Pixel Classic Regular`가 이어진다 (폰트 name table nameID 0의 원문 두 줄).
- 비고: 전문은 [SPDX OFL-1.1](https://spdx.org/licenses/OFL-1.1.html) 텍스트를 그대로 옮겼다. 공식 원문은 [openfontlicense.org](https://openfontlicense.org/open-font-license-official-text/)에 있다.

```text
SIL OPEN FONT LICENSE

Version 1.1 - 26 February 2007

PREAMBLE

The goals of the Open Font License (OFL) are to stimulate worldwide development of collaborative font projects, to support the font creation efforts of academic and linguistic communities, and to provide a free and open framework in which fonts may be shared and improved in partnership with others.

The OFL allows the licensed fonts to be used, studied, modified and redistributed freely as long as they are not sold by themselves. The fonts, including any derivative works, can be bundled, embedded, redistributed and/or sold with any software provided that any reserved names are not used by derivative works. The fonts and derivatives, however, cannot be released under any other type of license. The requirement for fonts to remain under this license does not apply to any document created using the fonts or their derivatives.

DEFINITIONS

"Font Software" refers to the set of files released by the Copyright Holder(s) under this license and clearly marked as such. This may include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the copyright statement(s).

"Original Version" refers to the collection of Font Software components as distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting, or substituting — in part or in whole — any of the components of the Original Version, by changing formats or by porting the Font Software to a new environment.

"Author" refers to any designer, engineer, programmer, technical writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS

Permission is hereby granted, free of charge, to any person obtaining a copy of the Font Software, to use, study, copy, merge, embed, modify, redistribute, and sell modified and unmodified copies of the Font Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components, in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled, redistributed and/or sold with any software, provided that each copy contains the above copyright notice and this license. These can be included either as stand-alone text files, human-readable headers or in the appropriate machine-readable metadata fields within text or binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font Name(s) unless explicit written permission is granted by the corresponding Copyright Holder. This restriction only applies to the primary font name as presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font Software shall not be used to promote, endorse or advertise any Modified Version, except to acknowledge the contribution(s) of the Copyright Holder(s) and the Author(s) or with their explicit written permission.

5) The Font Software, modified or unmodified, in part or in whole, must be distributed entirely under this license, and must not be distributed under any other license. The requirement for fonts to remain under this license does not apply to any document created using the Font Software.

TERMINATION

This license becomes null and void if any of the above conditions are not met.

DISCLAIMER

THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM OTHER DEALINGS IN THE FONT SOFTWARE.
```

## 업스트림 기여 에셋

경로는 `webview-ui/public/assets/` 기준이다.

| 에셋      | 대상                                  | 들어온 커밋                  | 비고                                                      |
| --------- | ------------------------------------- | ---------------------------- | --------------------------------------------------------- |
| 가구 25종 | `furniture/*`                         | `add749a` (#117, 2026-03-13) | 기존 에셋을 "fully open-source" 원본 에셋으로 교체한 커밋 |
| 바닥      | `floors/floor_0.png`–`floor_8.png`    | `add749a` (#117)             | 같은 커밋                                                 |
| 벽        | `walls/wall_0.png`                    | `11e37cb` (2026-02-21)       | `add749a`에서 `walls.png`의 이름만 바꿨다 (내용은 같다)   |
| 펫        | `pets/claudio/`, `pets/gitcat/`       | `928ccd4`                    | @itsManeka                                                |
| 카펫      | `carpets/carpet_0.png`–`carpet_2.png` | `7a1643f`                    | 업스트림 CHANGELOG는 카펫 시스템을 @NNTin에게 크레딧한다  |

- 라이선스와 저작권 표기: 저장소에 별도 라이선스가 없으므로 Pixel Agents MIT를 따르는 것으로 본다 (추정, 업스트림 확인 필요).

## 빌드 산출물의 npm 패키지

상속한 빌드 산출물은 제3자 npm 패키지를 번들한다. 예는 아래와 같다.

- `dist/webview`: React, Tailwind CSS가 생성한 CSS
- `dist/extension.js`: Fastify 계열과 그 의존성, `pngjs`
- `dist/cli.js`: `pngjs` (Fastify 계열은 설치할 때 받는다)

이 포크는 이 산출물을 배포하지 않는다. agents-room이 빌드를 배포할 때는 그 빌드에 실제로 들어간 패키지의 고지를 생성해 여기에 추가한다.

## 추가 예정 구성요소

아직 저장소에 없다. 배포에 포함할 때 버전과 라이선스를 다시 확인한다.

| 구성요소                                                                    | 라이선스                             | 비고                                                                                                                                                                                                                         |
| --------------------------------------------------------------------------- | ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Electron (데스크톱 셸)                                                      | 배포 시점에 확인해 기재한다          | Electron 배포물에 들어 있는 라이선스 파일(Chromium 포함)을 함께 싣는다 ([ADR 0001](docs/adr/0001-desktop-framework.md))                                                                                                      |
| JRE, Spring                                                                 | 배포 시점에 확인해 기재한다          | [ADR 0002](docs/adr/0002-java-spring-runtime.md)                                                                                                                                                                             |
| [ACP Java SDK](https://github.com/agentclientprotocol/java-sdk)             | Apache-2.0 (2026-10-20 기준)         | 로컬 런타임의 ACP Client                                                                                                                                                                                                     |
| [codex-acp](https://github.com/agentclientprotocol/codex-acp)               | Apache-2.0 (2026-10-23 확인)         | Codex(`@openai/codex`, Apache-2.0)를 함께 담는다. 설치 파일에 동봉한다 ([ADR 0003](docs/adr/0003-acp-integration.md) 결정 12)                                                                                                |
| [claude-agent-acp](https://github.com/agentclientprotocol/claude-agent-acp) | Apache-2.0 (어댑터, 2026-10-23 확인) | 의존하는 `@anthropic-ai/claude-agent-sdk`는 Anthropic 독점 라이선스("All rights reserved")이고 Claude Code 실행 파일을 담는다. 조건을 확인하기 전까지 동봉하지 않는다 ([ADR 0003](docs/adr/0003-acp-integration.md) 결정 12) |
