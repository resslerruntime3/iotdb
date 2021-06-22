 %
 % Licensed to the Apache Software Foundation (ASF) under one
 % or more contributor license agreements.  See the NOTICE file
 % distributed with this work for additional information
 % regarding copyright ownership.  The ASF licenses this file
 % to you under the Apache License, Version 2.0 (the
 % "License"); you may not use this file except in compliance
 % with the License.  You may obtain a copy of the License at
 %
 %     http://www.apache.org/licenses/LICENSE-2.0
 %
 % Unless required by applicable law or agreed to in writing,
 % software distributed under the License is distributed on an
 % "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 % KIND, either express or implied.  See the License for the
 % specific language governing permissions and limitations
 % under the License.
 %

clear all;close all;

% 1. load visdata generated by TsFileExtractDataToVisTool
filePath = 'D:\visdata1.csv';
[timeMap,countMap] = loadVisData(filePath,'ms'); % mind the timestamp unit

% 2. plot figures given the loaded data and two plot parameters:
% `showSpecific` and `isFileOrder`
draw(timeMap,countMap,{},false)

draw(timeMap,countMap,{},true)

draw(timeMap,countMap,{'root.vehicle.d0.s0'},false)

draw(timeMap,countMap,{'root.vehicle.d0.s0','root.vehicle.d0.s1'},false)

draw(timeMap,countMap,{'root.vehicle.d0.s0','root.vehicle.d0.s1'},true)

%% Functions
function [timeMap,countMap] = loadVisData(filePath,timestampUnit)
% Load visdata generated by TsFileExtractDataToVisTool.
%
% filePath: the path of visdata.
% The format is [tsName,fileName,versionNum,startTime,endTime,countNum].
% `tsName` and `fileName` are string, the others are long value.
% If the tsfile is unsequence file, `fileName` will contain "unseq" as an
% indicator, which is guaranteed by TsFileExtractDataToVisTool.
%
% timestampUnit(not case sensitive):
%   'us' if the timestamp is microsecond, e.g., 1621993620816000
%   'ms' if it is millisecond, e.g., 1621993620816
%   's' if it is second, e.g., 1621993620
%
% timeMap: record the time range of every chunk.
% Key [tsName][fileName][version] identifies the only chunk. Value is
% [startTime,endTime] of the chunk.
%
% countMap: record the point count number of every chunk. Key is the same
% as that of timeMap. Value is countNum.

    timeMap = containers.Map();
    countMap = containers.Map();
    A = readtable(filePath);
    for i=1:1:size(A,1)
        [timeMap,countMap]=addChunkTime(A{i,1}{1},A{i,2}{1},A{i,3},...
            A{i,4},A{i,5},A{i,6},timestampUnit,timeMap,countMap);
    end
    disp(['loadVisData finished. ','load ',num2str(size(timeMap,1)),' chunks in total'])
end

function [timeMap,countMap] = addChunkTime(tsName,fileName,versionNum,...
    startTime,endTime,countNum,timestampUnit,timeMap,countMap)
% Used by loadVisData function.

    % the key [tsName][fileName][version] identifies the only chunk
    key = ['[',tsName,'][', fileName,'][', num2str(versionNum),'V]'];

    startTime = convertLongToDate(startTime,timestampUnit);
    endTime = convertLongToDate(endTime,timestampUnit);
    timeMap(key) = [startTime,endTime];

    countMap(key) = countNum;
end

function draw(timeMap,countMap,showSpecific,isFileOrder)
% Plot figures given the loaded data and two plot parameters:
% `showSpecific` and `isFileOrder`.
%
% process: 1) traverse `keys(timeMap)` to get the position arrangements on
%          the y axis dynamically, which is defined simultaneously by
%           (a)`showSpecific`: traverse `keys(timeMap)`, filter out keys
%          that don't statisfy `showSpecific`.
%           (b) seqKey/unseqKey display policies: extract seqKey or unseqKey
%          from statisfied keys under different display policies:
%               b-1) unseqKey identifies tsName and fileName, so chunk data with the
%               same fileName and tsName but different versionNums are
%               plotted in the same line.
%               b-2) seqKey identifies tsName, so chunk data with the same tsName but
%               different fileNames and versionNums are ploteed in the same
%               line.
%           (c)`isFileOrder`: sort seqKey&unseqKey according to `isFileOrder`,
%          finally get the position arrangements on the y axis.
%          2) traverse `keys(timeMap)` again, get startTime&endTime from
%          `treeMap` as positions on the x axis, combined with the
%          positions on the y axis from the last step, finish plot.
%
% timeMap,countMap: generated by loadVisData function.
%
% showSpecific: the specific set of time series to be plotted.
%               If showSpecific is empty{}, then all loaded time series
%               will be plotted.
%               Note: Wildcard matching is not supported now. In other
%               words, showSpecific only support full time series path
%               names.
%
% isFileOrder: true to sort seqKeys&unseqKeys by fileName priority, false
%              to sort seqKeys&unseqKeys by tsName priority.

    % traverse `keys(timeMap)` to get the position arrangements on the y axis dynamically
    [yticklabelMap,allkeysBoolean]=get_yticklabelMaps(keys(timeMap),showSpecific, isFileOrder);
    disp('draw: get_yticklabelMaps finished')

    % traverse `keys(timeMap)` again to plot
    if all(allkeysBoolean(:)==false)
        disp('no statisfied specific data')
        return
    end
    figure,
    m=1;
    for k = keys(timeMap)
        if ~allkeysBoolean(m)
            m = m + 1; % don't forget this step
            continue;
        end
        key = k{1};
        % extract [tsName][fileName][versionNum] respectively
        timeMapKeySplit=split(key,["[","]","]["]);
        tsName = timeMapKeySplit{2};
        fileName = timeMapKeySplit{4};
        versionNumStr = timeMapKeySplit{6};
        % get the positions on the x axis
        timeRange = timeMap(key);
        % get the positions on the y axis
        if contains(key,'unseq') % unsequence tsfile
            % unseqKey identifies tsName and fileName, so data with the
            % same fileName and tsName but different versionNums are
            % ploteed on the same line.
            if isFileOrder
                % unseqKey is 'fileName/tsName'
                yPos = yticklabelMap([fileName,'/',tsName]);
            else
                % unseqKey is 'tsName/fileName'
                yPos = yticklabelMap([tsName,'/',fileName]);
            end
            % begin plot
            plot(timeRange,[yPos,yPos],'LineWidth',8),hold on
            text(timeRange(1),yPos-0.3,sprintf(num2str(countMap(key))),'FontSize',7),hold on
            text(timeRange(1),yPos+0.3,sprintf(['[',versionNumStr,']']),'FontSize',7),hold on
            % add short vertical lines to the two ends of timeRange
            plot([timeRange(1),timeRange(1)],[yPos,yPos+0.3]),hold on
            plot([timeRange(2),timeRange(2)],[yPos,yPos+0.3]),hold on
            m = m + 1;
        else % sequence tsfile
            % seqKey identifies tsName, so data with the same tsName but
            % different fileNames and versionNums are ploteed on the same
            % line.
            yPos = yticklabelMap(tsName);
            % begin plot
            plot(timeRange,[yPos,yPos],'LineWidth',8),hold on
            text(timeRange(1),yPos-0.3,sprintf(num2str(countMap(key))),'FontSize',7),hold on
            text(timeRange(1),yPos+0.3,sprintf(['[',versionNumStr,']']),'FontSize',7),hold on
            % add fileName text, since seqKey does not contain this information
            text(timeRange(1),yPos+0.5,sprintf(fileName),'FontSize',7),hold on
            % add short vertical lines to the two ends of timeRange
            plot([timeRange(1),timeRange(1)],[yPos,yPos+0.3]),hold on
            plot([timeRange(2),timeRange(2)],[yPos,yPos+0.3]),hold on
            m = m + 1;
        end
    end
    yticklabelRes = keys(yticklabelMap);
    yticklabelNum = size(yticklabelRes,2);
    yticks(1:1:yticklabelNum)
    yticklabels(yticklabelRes)
    xtickformat('yyyy-MM-dd HH:mm:ss.SSS')
    ylim([0 yticklabelNum+1])
    disp('draw finished')
end

function [yticklabelMap,allkeysBoolean]=get_yticklabelMaps(allkeys,showSpecific,isFileOrder)
% Used by draw function. Given two plot parameters: `showSpecific` and
% `isFileOrder`,it dynamically generate the position arrangements on
% the y axis.
%
% process: traverse `keys(timeMap)`, filter out keys that don't statisfy
%          `showSpecific`, extract seqKey or unseqKey from statisfied keys,
%          sort seqKey&unseqKey according to `isFileOrder` to get the position
%          arrangements on the y axis.
%
% allkeys: a 1*N cell array stores the keys of all loaded data. Key
%          [tsName][fileName][version] identifies the only chunk.
%
% showSpecific: the specific set of time series to be plotted.
%               If showSpecific is empty{}, then all loaded time series
%               will be plotted.
%               Note: Wildcard matching is not supported now. In other
%               words, showSpecific only support full time series path
%               names.
%
% isFileOrder: true to sort seqKeys&unseqKeys by fileName
%              priority('fileName/tsName'), false to sort seqKeys&unseqKeys
%              by tsName priority ('tsName/fileName').
%
% yticklabelMap: seqKey/unseqKey->yPos, record the position arrangements on
%               the y axis.
%
% allkeysBoolean: save the filtered result of allkeys to avoid repeated
%               computation later in the draw function.

    N=size(allkeys,2);
    allkeysBoolean = ones(1,N);
    m=1;
    seqOrUnseqKeys = {};
    for k = allkeys
        key = k{1};
        % extract [tsName][fileName][versionNum] respectively
        timeMapKeySplit=split(key,["[","]","]["]);
        tsName = timeMapKeySplit{2};
        fileName = timeMapKeySplit{4};
        % filter given showSpecific
        if ~isempty(showSpecific)&&~any(strcmp(showSpecific,tsName))
            % note that can not use "contains", because for example V1.C101
            % containes V1.C1
            allkeysBoolean(m)=false;
            m = m + 1;
            continue;
            % Note: Wildcard matching is not supported now. In other
            % words, showSpecific only support full time series path
            % names.
        end
        allkeysBoolean(m)=true;
        if contains(fileName,'unseq') %unsequence tsfile
            if isFileOrder
                unseqKey=[fileName,'/',tsName];
            else
                unseqKey=[tsName,'/', fileName];
            end
            if ~any(strcmp(seqOrUnseqKeys,unseqKey))
                seqOrUnseqKeys{end+1}=unseqKey;
            end
        else %sequence tsfile
            seqKey=tsName;
            if ~any(strcmp(seqOrUnseqKeys,seqKey))
                seqOrUnseqKeys{end+1}=seqKey;
            end
        end
        m = m + 1;
    end
    seqOrUnseqKeys = sort(seqOrUnseqKeys);
    yticklabelMap = containers.Map();
    yposCurr = 1;
    for yposKey = seqOrUnseqKeys
        yticklabelMap(yposKey{1}) = yposCurr;
        yposCurr = yposCurr + 1;
    end
end

function t=convertLongToDate(timestamp,timestampUnit)
% timestampUnit(not case sensitive):
%   'us' if the timestamp is microsecond, e.g., 1621993620816000
%   'ms' if it is millisecond, e.g., 1621993620816
%   's' if it is second, e.g., 1621993620

    timestampUnit = lower(timestampUnit);
    if strcmp(timestampUnit,'us')
        % microsecond, e.g., 1621993620816000
        posix_timestamp = timestamp/1000/1000;
        t=datetime(posix_timestamp,'convertfrom','posixtime', 'Format', 'yyyy-MM-dd HH:mm:ss.SSSSSS','TimeZone','local');
    elseif strcmp(timestampUnit,'ms')
        % millisecond, e.g., 1621993620816
        posix_timestamp = timestamp/1000;
        t=datetime(posix_timestamp,'convertfrom','posixtime', 'Format', 'yyyy-MM-dd HH:mm:ss.SSS','TimeZone','local');
    elseif strcmp(timestampUnit,'s')
        % second, e.g., 1621993620
        posix_timestamp = timestamp;
        t=datetime(posix_timestamp,'convertfrom','posixtime', 'Format', 'yyyy-MM-dd HH:mm:ss','TimeZone','local');
    else
        error('Wrong timestamp unit. It should be us/ms/s.')
    end
end