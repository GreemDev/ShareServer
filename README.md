# ShareServer

ShareX custom destination written in Java, by [Scarsz](https://github.com/Scarsz).

This fork has personal changes deployed to my website, and will not be PR'd to [Scarsz/ShareServer](https://github.com/Scarsz/ShareServer).

Be aware that this source contains hard-coded values (such as my own public-facing image URL). You should not push this to your own webserver without editing the source here.
Alternatively, you can use the original repository for deploying without editing source.

ShareX Setup:
Mirror [this](https://img.greemdev.net/pCxHcFsRpf/ShareX_2019-03-21_14-22-55.png), 
replacing the crossed out value with your personal key (and the URL, obviously).

The key is configured via a command-line argument:
`java -jar ShareServer.jar {key} {portToUse}`

**Note**: if portToUse is not set it will default to 8082, so you can omit that if you aren't currently using 8082.
