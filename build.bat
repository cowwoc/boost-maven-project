rem @echo off

rem *** @see http://stackoverflow.com/a/1978129/14731 ***
setlocal enableextensions enabledelayedexpansion
set version=%1
if [%1]==[] goto missing_version

rem *** @see http://stackoverflow.com/a/2340018/14731 and http://stackoverflow.com/a/1855020/14731 ***
for /f %%i in ('echo %version% ^| sed "s/\./_/g"') do set version_with_underscores=%%i
echo version_with_underscores=%version_with_underscores%
set filename=boost_%version_with_underscores%.7z

if not exist build mkdir build
cd build

if not exist download\build.marker (
  rmdir /q /s download 2>nul
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  mkdir download
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  wget -N http://sourceforge.net/projects/boost/files/boost/%version%/%filename%?use_mirror=autoselect -P download
  if %errorlevel% neq 0 exit /b %errorlevel%
  touch download\build.marker
)

if not exist unpack\build.marker (
  rmdir /q /s unpack 2>nul
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  mkdir unpack
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  7z x -aoa download\%filename% -ounpack
  if %errorlevel% neq 0 exit /b %errorlevel%
  touch unpack\build.marker
)

if not exist compile\build.marker (
  rmdir /q /s compile 2>nul
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  mkdir compile
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  echo Copying boost_%version_with_underscores% into compile directory...
  xcopy /s /exclude:unpack\build.marker unpack\boost_%version_with_underscores%\* compile\ >nul
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  cd compile
  call bootstrap.bat
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  for /f %%a in ('echo 32 64^| sed "s/ /\n/g"') do (
    for /f %%v in ('echo debug release^| sed "s/ /\n/g"') do (
        set address-model=%%a
        set variant=%%v
        
	echo Compiling !address-model!-!variant!...
  	call bjam address-model=!address-model! --stagedir=. --without-python --without-mpi --layout=system variant=!variant! link=shared threading=multi runtime-link=shared stage -j %NUMBER_OF_PROCESSORS% --hash
	if %errorlevel% neq 0 exit /b %errorlevel%
	rename lib lib!address-model!-!variant!
    )
  )
  
  cd ..
  touch compile\build.marker
)

if not exist package\build.marker (
  rmdir /q /s package 2>nul
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  mkdir package
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  cd package
  echo Copying headers into package directory...
  xcopy /s ..\compile\boost boost\ >nul
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  7z a -r boost-api.7z boost\*
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  rmdir /q /s boost
  if %errorlevel% neq 0 exit /b %errorlevel%
  
  rmdir /q /s lib 2>nul
  if %errorlevel% neq 0 exit /b %errorlevel%
	  
  for /f %%a in ('echo 32 64^| sed "s/ /\n/g"') do (
    for /f %%v in ('echo debug release^| sed "s/ /\n/g"') do (
          set address-model=%%a
    	  set variant=%%v
    	  if [!address-model!]==[32] (set architecture=i386) else set architecture=amd64
	  
	  echo Copying lib!address-model!-!variant! into package directory...
	  xcopy /s ..\compile\lib!address-model!-!variant! lib\ >nul
	  if %errorlevel% neq 0 exit /b %errorlevel%
	  
	  echo Packaging !address-model!-!variant!...
	  7z a -r boost-atomic-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_atomic*.lib lib\*boost_atomic*.dll lib\*boost_atomic*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-chrono-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_chrono*.lib lib\*boost_chrono*.dll lib\*boost_chrono*.pdb 
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-context-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_context*.lib lib\*boost_context*.dll lib\*boost_context*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-date-time-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_date_time*.lib lib\*boost_date_time*.dll lib\*boost_date_time*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-exception-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_exception*.lib
	  if %errorlevel% neq 0 exit /b %errorlevel%
	  
	  7z a -r boost-filesystem-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_filesystem*.lib lib\*boost_filesystem*.dll lib\*boost_filesystem*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-graph-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_graph*.lib lib\*boost_graph*.dll lib\*boost_graph*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-iostreams-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_iostreams*.lib lib\*boost_iostreams*.dll lib\*boost_iostreams*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-locale-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_locale*.lib lib\*boost_locale*.dll lib\*boost_locale*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-math-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_math*.lib lib\*boost_math*.dll lib\*boost_math*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-program-options-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_program_options*.lib lib\*boost_program_options*.dll lib\*boost_program_options*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-random-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_random*.lib lib\*boost_random*.dll lib\*boost_random*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-regex-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_regex*.lib lib\*boost_regex*.dll lib\*boost_regex*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-serialization-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_serialization*.lib lib\*boost_serialization*.dll lib\*boost_serialization*.pdb lib\*boost_wserialization*.lib lib\*boost_wserialization*.dll lib\*boost_wserialization*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-signals-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_signals*.lib lib\*boost_signals*.dll lib\*boost_signals*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-system-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_system*.lib lib\*boost_system*.dll lib\*boost_system*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-test-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_prg_exec_monitor*.lib lib\*boost_prg_exec_monitor*.dll lib\*boost_prg_exec_monitor*.pdb lib\*boost_unit_test_framework*.lib lib\*boost_unit_test_framework*.dll lib\*boost_unit_test_framework*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-thread-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_thread*.lib lib\*boost_thread*.dll lib\*boost_thread*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  7z a -r boost-wave-%version%-windows-!architecture!-msvc-!variant!.7z lib\*boost_wave*.lib lib\*boost_wave*.dll lib\*boost_wave*.pdb
	  if %errorlevel% neq 0 exit /b %errorlevel%

	  rmdir /q /s lib
	  if %errorlevel% neq 0 exit /b %errorlevel%
    )
  )
  cd ..
  
  touch package\build.marker
)

:success
exit /b 0

:missing_version
echo SYNTAX: build ^<version^>
exit /b 1